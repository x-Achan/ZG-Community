package com.zhiguang.feed.schema;

public final class FeedKeys {

    private FeedKeys() {}

    // 空 ZSet"的"占位
    public static final String INIT_MEMBER = "__INIT__";

    public static final String BIG_AUTHORS = "feed:authors:big"; //全局大v名单
    public static final String HOT_CANDIDATES = "feed:hot:candidates"; //全局热点帖子名单
    public static final String HOT_CURRENT_VERSION = "feed:hot:current-version"; //"热门 Feed 用版本化

    public static final String BIG_V_EPOCH = "feed:big-v:epoch";

    // 主页Feed ZSet
    public static String authorIndex(long authorId) {
        return "feed:author:" + authorId;
    }

    // 大 V拉，普通推 inbox
    public static String followingInbox(long userId) {
        return "feed:inbox:" + userId;
    }
    public static String pullAuthors(long userId) {
        return "feed:following:pull:" + userId;
    }

    // 分片缓存
    public static String postFragment(long postId) {
        return "feed:item:" + postId;
    }

    // 作者页面/关注页面/热门页面的版本号
    public static String authorVersion(long authorId) {
        return "feed:version:author:" + authorId;
    }
    public static String followingVersion(long userId) {
        return "feed:version:following:" + userId;
    }
    public static String hotRank(long version) {
        return "feed:hot:public:" + version;
    }

    // 页面缓存 + 标识
    public static String page(String identity) {
        return "feed:page:" + identity;
    }

    // Feed重建锁
    public static String authorLock(long authorId) {
        return "lock:feed:author:" + authorId;
    }
    public static String followingLock(long userId) {
        return "lock:feed:following:" + userId;
    }
    public static final String HOT_REBUILD_LOCK = "lock:feed:hot-rebuild";
}