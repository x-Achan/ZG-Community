-- 作者主页：creator_id + status + create_time + id
ALTER TABLE know_posts
    ADD INDEX idx_feed_author (
                               creator_id,
                               status,
                               create_time DESC,
                               id DESC
        );

-- 热门候选池：published + public + create_time
ALTER TABLE know_posts
    ADD INDEX idx_feed_public_candidate (
                                         status,
                                         visible,
                                         create_time DESC,
                                         id DESC
        );

-- 关注 Feed 重建
ALTER TABLE following
    ADD INDEX idx_feed_following_active (
                                         from_user_id,
                                         rel_status,
                                         to_user_id
        );

-- 普通作者推送时查询粉丝
ALTER TABLE following
    ADD INDEX idx_feed_follower_active (
                                        to_user_id,
                                        rel_status,
                                        from_user_id
        );