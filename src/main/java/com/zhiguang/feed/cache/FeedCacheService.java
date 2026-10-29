package com.zhiguang.feed.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.zhiguang.feed.model.FeedPageSnapshot;
import com.zhiguang.feed.model.FeedPostSnapshot;
import com.zhiguang.feed.schema.FeedKeys;
import com.zhiguang.knowpost.mapper.KnowPostMapper;
import com.zhiguang.knowpost.model.KnowPostFeedRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedCacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final KnowPostMapper knowPostMapper;
    private final Cache<String, FeedPageSnapshot> feedPageLocalCache;

    private static final long PAGE_TTL_SECONDS = 120;
    private static final long POST_TTL_SECONDS = 7200;

    // 请求页面缓存 page
    public FeedPageSnapshot getPage(String key) {
        FeedPageSnapshot local = feedPageLocalCache.getIfPresent(key);
        if (local != null) {
            return local;
        }

        String json = redis.opsForValue().get(FeedKeys.page(key));
        if (json == null) {
            return null;
        }

        try {
            FeedPageSnapshot page = objectMapper.readValue(json, FeedPageSnapshot.class);
            feedPageLocalCache.put(key, page);
            return page;
        } catch (Exception exception) {
            redis.delete(FeedKeys.page(key));
            return null;
        }
    }

    public void putPage(String key, FeedPageSnapshot page) {
        try {
            String json = objectMapper.writeValueAsString(page);
            long ttl = PAGE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(31);
            redis.opsForValue().set(FeedKeys.page(key), json, Duration.ofSeconds(ttl));
            feedPageLocalCache.put(key, page);
        } catch (Exception exception) {
            throw new IllegalStateException("Feed 页面序列化失败", exception);
        }
    }

    public Map<Long, FeedPostSnapshot> getPosts(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }

        List<Long> ids = postIds.stream().distinct().toList();
        List<String> keys = ids.stream().map(FeedKeys::postFragment).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        Map<Long, FeedPostSnapshot> result = new HashMap<>();
        List<Long> missing = new ArrayList<>();

        for (int i = 0; i < ids.size(); i++) {
            String json = values == null ? null : values.get(i);
            if (json == null) {
                missing.add(ids.get(i));
                continue;
            }
            try {
                result.put(ids.get(i), objectMapper.readValue(json, FeedPostSnapshot.class));
            } catch (Exception exception) {
                missing.add(ids.get(i));
            }
        }
        if (!missing.isEmpty()) {
            List<KnowPostFeedRow> rows = knowPostMapper.findFeedRowsByIds(missing);
            for (KnowPostFeedRow row : rows) {
                FeedPostSnapshot snapshot = toSnapshot(row);
                result.put(snapshot.id(), snapshot);
                putPost(snapshot);
            }
        }
        return result;
    }

    public void refreshPost(long postId) {
        KnowPostFeedRow row = knowPostMapper.findFeedRowById(postId);
        if (row == null || "deleted".equals(row.getStatus())) {
            evictPost(postId);
            return;
        }
        putPost(toSnapshot(row));
    }

    // 写回 分片缓存
    public void putPost(FeedPostSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            long ttl = POST_TTL_SECONDS + ThreadLocalRandom.current().nextLong(601);
            redis.opsForValue().set(FeedKeys.postFragment(snapshot.id()), json, Duration.ofSeconds(ttl));
        } catch (Exception exception) {
            throw new IllegalStateException("帖子片段序列化失败", exception);
        }
    }

    public void evictPost(long postId) {
        try {
            redis.delete(FeedKeys.postFragment(postId));
        } catch (Exception exception) {
            log.warn("删除 Feed 帖子片段失败 postId={}", postId, exception);
        }
    }

    private FeedPostSnapshot toSnapshot(KnowPostFeedRow row) {
        return new FeedPostSnapshot(
                row.getId(),
                row.getCreatorId(),
                row.getTitle(),
                row.getDescription(),
                parseArray(row.getTags()),
                parseArray(row.getImgUrls()),
                row.getAuthorAvatar(),
                row.getAuthorNickname(),
                row.getAuthorTagJson(),
                row.getStatus(),
                row.getVisible(),
                row.getType(),
                Boolean.TRUE.equals(row.getIsTop()),
                row.getCreateTime(),
                row.getPublishTime()
        );
    }

    private List<String> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<List<String>>() {}
            );
        } catch (Exception exception) {
            return List.of();
        }
    }
}