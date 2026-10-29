package com.zhiguang.feed.service;

import com.zhiguang.feed.cache.FeedCacheService;
import com.zhiguang.feed.event.PostProjectionEvent;
import com.zhiguang.feed.schema.FeedKeys;
import com.zhiguang.feed.service.impl.FeedIndexService;
import com.zhiguang.knowpost.mapper.KnowPostMapper;
import com.zhiguang.knowpost.model.KnowPost;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FeedProjectionService {

    private final KnowPostMapper knowPostMapper;
    private final FeedIndexService feedIndexService;
    private final FeedCacheService feedCacheService;
    private final StringRedisTemplate redis;

    public void projectPost(PostProjectionEvent event) {
        KnowPost post = knowPostMapper.findById(event.postId());

        /*
         * 不相信事件中的旧快照，只相信当前数据库状态。
         */
        // 帖子不存在/已删除
        if (post == null || "deleted".equals(post.getStatus())) {
            if (post != null) {
                feedIndexService.removeAuthorPost(post);
                if (feedIndexService.isBigAuthor(post.getCreatorId())) {
                    feedIndexService.bumpBigVEpoch();
                } else {
                    feedIndexService.projectOrdinaryPost(post, false);
                }
            }
            feedIndexService.removeHotCandidate(event.postId());
            feedCacheService.evictPost(event.postId());
            return;
        }

        feedIndexService.upsertAuthorPost(post);
        feedCacheService.refreshPost(post.getId());

        boolean publicPublished = "published".equals(post.getStatus()) && "public".equals(post.getVisible());

        if (publicPublished) {
            feedIndexService.addHotCandidate(post);
        } else {
            feedIndexService.removeHotCandidate(post.getId());
        }

        boolean followingEligible = "published".equals(post.getStatus()) && ("public".equals(post.getVisible()) || "followers".equals(post.getVisible()));

        if (feedIndexService.isBigAuthor(post.getCreatorId())) {
            redis.opsForSet().add(FeedKeys.BIG_AUTHORS, String.valueOf(post.getCreatorId()));
            feedIndexService.bumpBigVEpoch();
        } else {
            feedIndexService.projectOrdinaryPost(post, followingEligible);
        }
    }
}