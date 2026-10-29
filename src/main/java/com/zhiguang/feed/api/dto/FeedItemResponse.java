package com.zhiguang.feed.api.dto;

import java.time.Instant;
import java.util.List;

public record FeedItemResponse(
        String id,
        String authorId,
        String title,
        String description,
        List<String> tags,
        List<String> imageUrls,
        String authorAvatar,
        String authorNickname,
        String authorTagJson,
        long likeCount,
        long favoriteCount,
        boolean liked,
        boolean favorited,
        boolean top,
        String status,
        String visible,
        String type,
        Instant createTime,
        Instant publishTime
) {}