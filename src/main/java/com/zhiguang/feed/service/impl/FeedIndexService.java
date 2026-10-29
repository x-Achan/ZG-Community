package com.zhiguang.feed.service.impl;

import com.zhiguang.feed.model.FeedCandidate;
import com.zhiguang.feed.model.FeedCursor;
import com.zhiguang.feed.schema.FeedKeys;
import com.zhiguang.knowpost.mapper.KnowPostMapper;
import com.zhiguang.knowpost.model.KnowPost;
import com.zhiguang.knowpost.model.KnowPostFeedRow;
import com.zhiguang.relation.mapper.RelationMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class FeedIndexService {

    public static final long BIG_V_THRESHOLD = 10_000L;    // 大V 阈值
    private static final int INBOX_REBUILD_SIZE = 1_000;   // 收件箱重建上限
    private static final int FOLLOW_BACKFILL_SIZE = 100;   // 关注回填上限
    private static final long INDEX_TTL_DAYS = 7;          // 索引 TTL 7 天

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final KnowPostMapper knowPostMapper;
    private final RelationMapper relationMapper;

    public List<FeedCandidate> readAuthor(long authorId, FeedCursor cursor, int limit) {
        ensureAuthor(authorId);
        return readZSet(FeedKeys.authorIndex(authorId), cursor, limit);
    }

    public List<FeedCandidate> readFollowing(long userId, FeedCursor cursor, int limit) {
        ensureFollowing(userId);
        List<FeedCandidate> candidates = new ArrayList<>();
        candidates.addAll(readZSet(FeedKeys.followingInbox(userId), cursor, limit));
        Set<String> pullAuthors = redis.opsForSet().members(FeedKeys.pullAuthors(userId));

        if (pullAuthors != null) {
            for (String value : pullAuthors) {
                if (FeedKeys.INIT_MEMBER.equals(value)) {
                    continue;
                }
                long authorId = Long.parseLong(value);
                candidates.addAll(readAuthor(authorId, cursor, limit));
            }
        }

        return candidates.stream().collect(
                        java.util.stream.Collectors.toMap(FeedCandidate::postId,
                                candidate -> candidate,
                                (left, right) -> left.score() >= right.score() ? left : right)).values().stream().sorted(candidateComparator()).limit(limit).toList();
    }

    public List<FeedCandidate> readHot(long version, FeedCursor cursor, int limit) {
        return readZSet(FeedKeys.hotRank(version), cursor, limit);
    }

    public void ensureAuthor(long authorId) {
        String key = FeedKeys.authorIndex(authorId);
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            redis.expire(key, Duration.ofDays(INDEX_TTL_DAYS));
            return;
        }
        RLock lock = redisson.getLock(FeedKeys.authorLock(authorId));
        boolean locked = false;
        try {
            locked = lock.tryLock(2, 15, TimeUnit.SECONDS);
            if (!locked || Boolean.TRUE.equals(redis.hasKey(key))) {
                return;
            }
            redis.opsForZSet().add(key, FeedKeys.INIT_MEMBER, Double.NEGATIVE_INFINITY);
            for (KnowPost post : knowPostMapper.listAuthorPostsForFeedIndex(authorId)) {
                redis.opsForZSet().add(
                        key,
                        String.valueOf(post.getId()),
                        post.getCreateTime().toEpochMilli());
            }
            redis.expire(key, Duration.ofDays(INDEX_TTL_DAYS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void ensureFollowing(long userId) {
        String inboxKey = FeedKeys.followingInbox(userId);
        String pullKey = FeedKeys.pullAuthors(userId);

        if (Boolean.TRUE.equals(redis.hasKey(inboxKey)) && Boolean.TRUE.equals(redis.hasKey(pullKey))) {
            return;
        }

        RLock lock = redisson.getLock(FeedKeys.followingLock(userId));
        boolean locked = false;

        try {
            locked = lock.tryLock(2, 20, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }

            Set<Long> bigAuthors = new HashSet<>(relationMapper.listFollowedBigAuthorIds(userId, BIG_V_THRESHOLD));

            redis.delete(pullKey);
            redis.opsForSet().add(pullKey, FeedKeys.INIT_MEMBER);

            for (Long authorId : bigAuthors) {
                redis.opsForSet().add(pullKey, String.valueOf(authorId));
                redis.opsForSet().add(FeedKeys.BIG_AUTHORS, String.valueOf(authorId));
            }

            redis.delete(inboxKey);
            redis.opsForZSet().add(inboxKey, FeedKeys.INIT_MEMBER, Double.NEGATIVE_INFINITY);
            List<KnowPostFeedRow> recent = knowPostMapper.listRecentFollowingPosts(userId, INBOX_REBUILD_SIZE);

            for (KnowPostFeedRow row : recent) {
                if (bigAuthors.contains(row.getCreatorId())) {
                    continue;
                }
                redis.opsForZSet().add(inboxKey, String.valueOf(row.getId()), row.getCreateTime().toEpochMilli());
            }

            redis.expire(inboxKey, Duration.ofDays(INDEX_TTL_DAYS));
            redis.expire(pullKey, Duration.ofDays(INDEX_TTL_DAYS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void upsertAuthorPost(KnowPost post) {
        String key = FeedKeys.authorIndex(post.getCreatorId());
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            redis.opsForZSet().add(
                    key,
                    String.valueOf(post.getId()),
                    post.getCreateTime().toEpochMilli());
        }
        bumpAuthorVersion(post.getCreatorId());
    }

    public void removeAuthorPost(KnowPost post) {
        redis.opsForZSet().remove(
                FeedKeys.authorIndex(post.getCreatorId()),
                String.valueOf(post.getId()));
        bumpAuthorVersion(post.getCreatorId());
    }

    public void addHotCandidate(KnowPost post) {
        redis.opsForZSet().add(
                FeedKeys.HOT_CANDIDATES,
                String.valueOf(post.getId()),
                post.getCreateTime().toEpochMilli());
    }

    public void removeHotCandidate(long postId) {
        redis.opsForZSet().remove(
                FeedKeys.HOT_CANDIDATES,
                String.valueOf(postId)
        );
    }

    public boolean isBigAuthor(long authorId) {
        return relationMapper.countFollowerActive(authorId)
                >= BIG_V_THRESHOLD;
    }

    public void projectOrdinaryPost(KnowPost post, boolean eligible) {
        List<Long> followers = relationMapper.listFollowerIds(post.getCreatorId());

        for (Long followerId : followers) {
            String inbox = FeedKeys.followingInbox(followerId);

            if (eligible) {
                redis.opsForZSet().add(
                        inbox,
                        String.valueOf(post.getId()),
                        post.getCreateTime().toEpochMilli()
                );
            } else {
                redis.opsForZSet().remove(
                        inbox,
                        String.valueOf(post.getId())
                );
            }

            trimInbox(inbox);
            bumpFollowingVersion(followerId);
        }
    }

    public void projectRelation(
            long followerId,
            long authorId,
            boolean following
    ) {
        boolean big = isBigAuthor(authorId);
        String pullKey = FeedKeys.pullAuthors(followerId);
        String inboxKey = FeedKeys.followingInbox(followerId);

        if (following && big) {
            redis.opsForSet().add(
                    pullKey,
                    String.valueOf(authorId)
            );
            redis.opsForSet().add(
                    FeedKeys.BIG_AUTHORS,
                    String.valueOf(authorId)
            );
        } else if (!following && big) {
            redis.opsForSet().remove(
                    pullKey,
                    String.valueOf(authorId)
            );
        } else if (following) {
            for (KnowPostFeedRow row : knowPostMapper.listRecentVisiblePostsByAuthor(authorId, FOLLOW_BACKFILL_SIZE)) {
                redis.opsForZSet().add(
                        inboxKey,
                        String.valueOf(row.getId()),
                        row.getCreateTime().toEpochMilli()
                );
            }
            trimInbox(inboxKey);
        } else {
            ensureAuthor(authorId);
            Set<String> ids = redis.opsForZSet().range(FeedKeys.authorIndex(authorId), 0, -1);

            if (ids != null && !ids.isEmpty()) {
                redis.opsForZSet().remove(inboxKey, ids.toArray());
            }
        }

        bumpFollowingVersion(followerId);
    }

    public long authorVersion(long authorId) {
        return readVersion(FeedKeys.authorVersion(authorId));
    }

    public long followingVersion(long userId) {
        return readVersion(FeedKeys.followingVersion(userId));
    }

    public long bigVEpoch() {
        return readVersion(FeedKeys.BIG_V_EPOCH);
    }

    public void bumpBigVEpoch() {
        redis.opsForValue().increment(FeedKeys.BIG_V_EPOCH);
    }

    private void bumpAuthorVersion(long authorId) {
        redis.opsForValue().increment(
                FeedKeys.authorVersion(authorId)
        );
    }

    private void bumpFollowingVersion(long userId) {
        redis.opsForValue().increment(
                FeedKeys.followingVersion(userId)
        );
    }

    private long readVersion(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }

    private List<FeedCandidate> readZSet(String key, FeedCursor cursor, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeByScoreWithScores(
                        key,
                        0,
                        cursor.score(),
                        0,
                        Math.max(limit * 3L, limit)
                );

        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }

        List<FeedCandidate> result = new ArrayList<>();

        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String member = tuple.getValue();

            if (member == null || FeedKeys.INIT_MEMBER.equals(member) || tuple.getScore() == null) {
                continue;
            }

            long postId = Long.parseLong(member);
            double score = tuple.getScore();

            boolean afterCursor = score < cursor.score() || (Double.compare(score, cursor.score()) == 0 && postId < cursor.postId());

            if (afterCursor) {
                result.add(new FeedCandidate(postId, score));
            }
        }

        return result.stream().sorted(candidateComparator()).limit(limit).toList();
    }

    private Comparator<FeedCandidate> candidateComparator() {
        return Comparator.comparingDouble(FeedCandidate::score)
                .reversed()
                .thenComparing(
                        FeedCandidate::postId,
                        Comparator.reverseOrder()
                );
    }

    private void trimInbox(String key) {
        Long size = redis.opsForZSet().zCard(key);
        if (size != null && size > INBOX_REBUILD_SIZE + 1L) {
            redis.opsForZSet().removeRange(
                    key,
                    0,
                    size - INBOX_REBUILD_SIZE - 2L
            );
        }
    }
}