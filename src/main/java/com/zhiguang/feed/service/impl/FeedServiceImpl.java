package com.zhiguang.feed.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.counter.model.CounterType;
import com.zhiguang.counter.service.CounterService;
import com.zhiguang.feed.api.dto.FeedItemResponse;
import com.zhiguang.feed.api.dto.FeedPageResponse;
import com.zhiguang.feed.cache.FeedCacheService;
import com.zhiguang.feed.model.*;
import com.zhiguang.feed.service.FeedService;
import com.zhiguang.knowpost.service.HotRankService;
import com.zhiguang.relation.service.RelationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final int MAX_SCAN_ROUNDS = 10;

    private final FeedIndexService feedIndexService;
    private final FeedCacheService feedCacheService;
    private final HotRankService hotRankService;
    private final CounterService counterService;
    private final RelationService relationService;
    private final ObjectMapper objectMapper;

    // ========================= 4 种 Feed 入口 =========================

    @Override
    public FeedPageResponse hot(String cursorValue, int size, Long viewerId) {
        int safeSize = safeSize(size);
        FeedCursor cursor;
        long version;

        // 首次访问 热门 Feed时，cursorValue 是空的
        if (cursorValue == null || cursorValue.isBlank()) {
            // 从Redis中读出当前 热门Feed版本
            version = hotRankService.currentVersion();
            // 游标就是 首页游标
            cursor = FeedCursor.firstHot(version);
        } else {
            cursor = decode(cursorValue); // 将 cursor 从String 转化为 FeedCorsur对象
            requireKind(cursor, "HOT");
            version = cursor.version(); // 翻页时沿用旧版本号
        }

        String pageKey = "hot:" + version + ":" + safeSize + ":" + cursorToken(cursorValue);

        return readPage(
                pageKey,
                cursor,
                safeSize,
                next -> feedIndexService.readHot(version, next, safeSize * 3),
                post -> post.published() && post.publicVisible(),
                viewerId,
                false
        );
    }
// 我的主页和他人主页逻辑复用
    @Override
    public FeedPageResponse mine(long viewerId, String cursorValue, int size) {
        return profile(viewerId, viewerId, cursorValue, size);
    }

    @Override
    public FeedPageResponse profile(long authorId, long viewerId, String cursorValue, int size) {
        int safeSize = safeSize(size);
        FeedCursor cursor = timeCursor(cursorValue);

        boolean owner = authorId == viewerId;
        boolean following = !owner && relationService.isFollowing(viewerId, authorId);
        // scope：自己，粉丝，路人，scope是要写进 key 的
        String scope = owner ? "owner" : (following ? "follower" : "public");

        long version = feedIndexService.authorVersion(authorId);
        String pageKey = "profile:" + authorId + ":" + scope + ":" + version
                + ":" + safeSize + ":" + cursorToken(cursorValue);

        Predicate<FeedPostSnapshot> permission;
        if (owner) {
            permission = post -> !post.deleted();
        } else if (following) {
            permission = post -> post.published() && (post.publicVisible() || post.followersVisible());
        } else {
            permission = post -> post.published() && post.publicVisible();
        }

        return readPage(
                pageKey,
                cursor,
                safeSize,
                next -> feedIndexService.readAuthor(authorId, next, safeSize * 3),
                permission,
                viewerId,
                owner
        );
    }

    @Override
    public FeedPageResponse following(long viewerId, String cursorValue, int size) {
        int safeSize = safeSize(size);
        FeedCursor cursor = timeCursor(cursorValue);

        long userVersion = feedIndexService.followingVersion(viewerId);
        long bigVEpoch = feedIndexService.bigVEpoch();

        String pageKey = "following:" + viewerId + ":" + userVersion
                + ":" + bigVEpoch + ":" + safeSize + ":" + cursorToken(cursorValue);

        return readPage(
                pageKey,
                cursor,
                safeSize,
                next -> feedIndexService.readFollowing(viewerId, next, safeSize * 3),
                post -> post.published()
                        && (post.publicVisible() || post.followersVisible()),
                viewerId,
                false
        );
    }

    // ========================= 核心：读一页 =========================

    private FeedPageResponse readPage(
            String pageKey,
            FeedCursor initialCursor,
            int size,
            Function<FeedCursor, List<FeedCandidate>> candidateLoader,
            Predicate<FeedPostSnapshot> permission,
            Long viewerId,
            boolean ownerView) {

        // ① 页面缓存命中 → 直接组装
        FeedPageSnapshot cached = feedCacheService.getPage(pageKey);
        if (cached != null) {
            return hydrate(cached, permission, viewerId, ownerView);
        }

        // ② 未命中 → 从 ZSet 拿候选，过滤，凑够 size
        List<Long> accepted = new ArrayList<>(size + 1);
        FeedCursor scanCursor = initialCursor;
        FeedCursor lastAcceptedCursor = null;

        for (int round = 0; round < MAX_SCAN_ROUNDS && accepted.size() <= size; round++) {
            // 从ZSet中取
            List<FeedCandidate> candidates = candidateLoader.apply(scanCursor);
            if (candidates.isEmpty()) break;

            List<Long> ids = candidates.stream().map(FeedCandidate::postId).toList();
            Map<Long, FeedPostSnapshot> posts = feedCacheService.getPosts(ids); //读取分片缓存

            for (FeedCandidate candidate : candidates) {
                scanCursor = nextCursor(initialCursor, candidate);

                FeedPostSnapshot post = posts.get(candidate.postId());
                if (post == null || !permission.test(post)) continue;

                accepted.add(candidate.postId());
                if (accepted.size() <= size) {
                    lastAcceptedCursor = nextCursor(initialCursor, candidate);
                }
                if (accepted.size() > size) break;
            }

            if (candidates.size() < size * 3) break;
        }

        // ③ hasMore + nextCursor
        boolean hasMore = accepted.size() > size;
        if (hasMore) {
            accepted = new ArrayList<>(accepted.subList(0, size));
        }
        String nextCursor = (hasMore && lastAcceptedCursor != null)
                ? encode(lastAcceptedCursor)
                : null;

        // ④ 写页面缓存
        FeedPageSnapshot page = new FeedPageSnapshot(List.copyOf(accepted), nextCursor, hasMore);
        feedCacheService.putPage(pageKey, page);

        return hydrate(page, permission, viewerId, ownerView);
    }

    // ========================= 组装响应 =========================

    private FeedPageResponse hydrate(
            FeedPageSnapshot page,
            Predicate<FeedPostSnapshot> permission,
            Long viewerId,
            boolean ownerView
    ) {
        Map<Long, FeedPostSnapshot> posts = feedCacheService.getPosts(page.postIds());

        List<Long> visibleIds = page.postIds().stream()
                .filter(id -> {
                    FeedPostSnapshot post = posts.get(id);
                    return post != null && permission.test(post);
                })
                .toList();

        Map<Long, Long> likes = counterService.getCounts(visibleIds, CounterType.LIKE);
        Map<Long, Long> favorites = counterService.getCounts(visibleIds, CounterType.FAV);

        Set<Long> liked = viewerId == null
                ? Set.of()
                : counterService.getActionPostIds(visibleIds, CounterType.LIKE, viewerId);
        Set<Long> favorited = viewerId == null
                ? Set.of()
                : counterService.getActionPostIds(visibleIds, CounterType.FAV, viewerId);

        List<FeedItemResponse> items = new ArrayList<>(visibleIds.size());
        for (Long id : visibleIds) {
            FeedPostSnapshot post = posts.get(id);
            items.add(new FeedItemResponse(
                    String.valueOf(post.id()),
                    String.valueOf(post.creatorId()),
                    post.title(),
                    post.description(),
                    post.tags(),
                    post.imageUrls(),
                    post.authorAvatar(),
                    post.authorNickname(),
                    post.authorTagJson(),
                    likes.getOrDefault(id, 0L),
                    favorites.getOrDefault(id, 0L),
                    liked.contains(id),
                    favorited.contains(id),
                    post.top(),
                    ownerView ? post.status() : null,
                    ownerView ? post.visible() : null,
                    post.type(),
                    post.createTime(),
                    post.publishTime()
            ));
        }

        return new FeedPageResponse(items, page.nextCursor(), page.hasMore());
    }

    // ========================= 游标 =========================

    private FeedCursor timeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return FeedCursor.firstTime();
        }
        FeedCursor decoded = decode(cursor);
        requireKind(decoded, "TIME");
        return decoded;
    }

    private FeedCursor nextCursor(FeedCursor original, FeedCandidate candidate) {
        if ("HOT".equals(original.kind())) {
            return FeedCursor.hot(original.version(), candidate.score(), candidate.postId());
        }
        return FeedCursor.time(candidate.score(), candidate.postId());
    }

    private String encode(FeedCursor cursor) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("游标编码失败", e);
        }
    }

    private FeedCursor decode(String value) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(value);
            return objectMapper.readValue(json, FeedCursor.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("游标无效");
        }
    }

    private void requireKind(FeedCursor cursor, String expected) {
        if (!expected.equals(cursor.kind())) {
            throw new IllegalArgumentException("游标类型错误");
        }
    }

    // ========================= 工具 =========================

    private int safeSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private String cursorToken(String cursor) {
        return (cursor == null || cursor.isBlank()) ? "FIRST" : cursor;
    }
}