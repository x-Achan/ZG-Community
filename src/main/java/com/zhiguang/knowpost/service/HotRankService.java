package com.zhiguang.knowpost.service;

import com.zhiguang.counter.model.CounterType;
import com.zhiguang.counter.service.CounterService;
import com.zhiguang.feed.cache.FeedCacheService;
import com.zhiguang.feed.model.FeedPostSnapshot;
import com.zhiguang.feed.schema.FeedKeys;
import com.zhiguang.knowpost.mapper.KnowPostMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class HotRankService {

    private static final int MAX_CANDIDATES = 20_000;    // 候选上限
    private static final int CANDIDATE_DAYS = 30;         // 候选最近 30 天

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final KnowPostMapper knowPostMapper;
    private final FeedCacheService feedCacheService;
    private final CounterService counterService;

    // 读当前版本
    public long currentVersion() {
        String value = redis.opsForValue().get(FeedKeys.HOT_CURRENT_VERSION);
        if (value != null && Boolean.TRUE.equals(redis.hasKey(FeedKeys.hotRank(Long.parseLong(value))))) {
            return Long.parseLong(value);
        }
        rebuild();
        String rebuilt = redis.opsForValue().get(FeedKeys.HOT_CURRENT_VERSION);
        return rebuilt == null ? System.currentTimeMillis() / 60_000L : Long.parseLong(rebuilt);
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 10_000)
    public void scheduledRebuild() {
        rebuild();
    }

    public void rebuild() {
        RLock lock = redisson.getLock(FeedKeys.HOT_REBUILD_LOCK);
        boolean locked = false;

        try {
            locked = lock.tryLock();
            if (!locked) return;

            // 确保"候选集"存在
            ensureCandidates();
             //  拿"候选集的 ID"（最多 2 万）
            Set<String> members = redis.opsForZSet().reverseRange(FeedKeys.HOT_CANDIDATES, 0, MAX_CANDIDATES - 1L);

            if (members == null) {
                members = Set.of();
            }

            List<Long> ids = members.stream().map(Long::parseLong).toList();

            // 批量拿"帖子分片"
            Map<Long, FeedPostSnapshot> posts = feedCacheService.getPosts(ids);
            // 批量拿"点赞数 / 收藏数"
            Map<Long, Long> likes = counterService.getCounts(ids, CounterType.LIKE);
            Map<Long, Long> favorites = counterService.getCounts(ids, CounterType.FAV);

            // 版本 = "当前分钟"
            long version = System.currentTimeMillis() / 60_000L;
            String rankKey = FeedKeys.hotRank(version);

            //  删旧榜（如果版本号相同，覆盖）
            redis.delete(rankKey);

            // 逐个算"热门分数"
            Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
            Instant now = Instant.now();

            for (Long id : ids) {
                FeedPostSnapshot post = posts.get(id);

                //  过滤：非"公开 + 已发布" → 跳过
                if (post == null || !post.published() || !post.publicVisible()) {
                    continue;
                }

                // ageHours
                double ageHours = Math.max(0,
                        Duration.between(post.publishTime() == null ? post.createTime() : post.publishTime(), now).toMinutes() / 60.0);

                // 互动分
                double interaction =
                        likes.getOrDefault(id, 0L) * 3.0
                        + favorites.getOrDefault(id, 0L) * 5.0
                        + 1.0;
                // 时间衰减
                double score = interaction / Math.pow(ageHours + 2.0, 1.5);
                tuples.add(ZSetOperations.TypedTuple.of(String.valueOf(id), score));
            }

            // 写新榜
            if (!tuples.isEmpty()) {
                redis.opsForZSet().add(rankKey, tuples);
            }
            /*
             * 游标中的旧 version 还能继续使用，
             * 所以旧榜保留 30 分钟。
             */
            redis.expire(rankKey, Duration.ofMinutes(30));
            // 更新"当前版本"
            redis.opsForValue().set(FeedKeys.HOT_CURRENT_VERSION, String.valueOf(version));
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void ensureCandidates() {
        if (Boolean.TRUE.equals(redis.hasKey(FeedKeys.HOT_CANDIDATES))) {
            return;
        }
        List<Long> ids = knowPostMapper.listPublicHotCandidateIds(
                        Instant.now().minus(CANDIDATE_DAYS, ChronoUnit.DAYS),
                        MAX_CANDIDATES);

        Map<Long, FeedPostSnapshot> posts = feedCacheService.getPosts(ids);

        for (Long id : ids) {
            FeedPostSnapshot post = posts.get(id);
            if (post != null) {
                redis.opsForZSet().add(FeedKeys.HOT_CANDIDATES, String.valueOf(id), post.createTime().toEpochMilli());
            }
        }
    }
}