package com.zhiguang.feed.api.dto;

import java.util.List;

// feed流 返回响应
public record FeedPageResponse(
        List<FeedItemResponse> items, //页面卡片信息
        String nextCursor, // 下一页的游标
        boolean hasMore
) {}