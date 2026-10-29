package com.zhiguang.feed.model;

/**
 * Feed 游标。
 *
 * kind:
 * TIME = 创建时间排序
 * HOT  = 热度排序
 *
 * version 只在热门 Feed 中使用，用来固定本次浏览的热门榜快照。
 */
public record FeedCursor(
        String kind,
        long version,  //version 只在热门 Feed 中使用
        double score,
        long postId
) {

    // 首次访问，首页 -> 初始游标 cursor：score和 postId都是最大值
    public static FeedCursor firstTime() {
        return new FeedCursor("TIME", 0L, Double.MAX_VALUE, Long.MAX_VALUE);
    }

    public static FeedCursor firstHot(long version) {
        return new FeedCursor("HOT", version, Double.MAX_VALUE, Long.MAX_VALUE);
    }

    public static FeedCursor time(double score, long postId) {
        return new FeedCursor("TIME", 0L, score, postId);
    }

    public static FeedCursor hot(long version, double score, long postId) {
        return new FeedCursor("HOT", version, score, postId);
    }
}