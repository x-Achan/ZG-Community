package com.zhiguang.feed.model;

import java.time.Instant;
import java.util.List;

/**
 * Redis 帖子片段。
 *
 * 不缓存正文，不缓存 contentUrl，不缓存用户点赞状态。
 * Feed 页面只需要这些卡片信息。
 */
public record FeedPostSnapshot(
        long id,
        long creatorId,
        String title,
        String description,
        List<String> tags,
        List<String> imageUrls,
        String authorAvatar,
        String authorNickname,
        String authorTagJson,
        String status,
        String visible,
        String type,
        boolean top,
        Instant createTime,
        Instant publishTime
) {

    public boolean deleted() {
        return "deleted".equals(status);
    }

    public boolean published() {
        return "published".equals(status);
    }

    public boolean publicVisible() {
        return "public".equals(visible);
    }

    public boolean followersVisible() {
        return "followers".equals(visible);
    }
}