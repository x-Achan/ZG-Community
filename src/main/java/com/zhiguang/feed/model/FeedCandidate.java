package com.zhiguang.feed.model;

/**
 * 排序索引返回的候选帖子。
 *
 * score：
 * - 主页/关注流：createTime 毫秒值
 * - 热门流：hotScore
 */
public record FeedCandidate(
        long postId,
        double score
) {}