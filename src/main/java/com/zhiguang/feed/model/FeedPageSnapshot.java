package com.zhiguang.feed.model;

import java.util.List;

/**
 * 页面快照
 * 页面缓存只保存帖子 ID 顺序和下一页游标。
 */
public record FeedPageSnapshot(
        List<Long> postIds,
        String nextCursor,
        boolean hasMore
) {}