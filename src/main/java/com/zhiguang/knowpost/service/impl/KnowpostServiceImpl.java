package com.zhiguang.knowpost.service.impl;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.counter.model.CounterType;
import com.zhiguang.counter.service.CounterService;
import com.zhiguang.feed.cache.FeedCacheService;
import com.zhiguang.feed.event.PostProjectionEvent;
import com.zhiguang.knowpost.api.dto.KnowPostDetailResponse;
import com.zhiguang.knowpost.api.dto.KnowPostDraftCreateResponse;
import com.zhiguang.knowpost.api.dto.KnowPostPatchRequest;
import com.zhiguang.knowpost.id.SnowflakeIdGenerator;
import com.zhiguang.knowpost.mapper.KnowPostMapper;
import com.zhiguang.knowpost.model.KnowPost;
import com.zhiguang.knowpost.model.KnowPostDetailRow;
import com.zhiguang.knowpost.service.KnowPostService;
import com.zhiguang.relation.event.OutboxTopics;
import com.zhiguang.relation.outbox.OutboxMapper;
import com.zhiguang.relation.service.RelationService;
import com.zhiguang.storage.OssStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowpostServiceImpl implements KnowPostService {

    private final SnowflakeIdGenerator idGen;
    private final KnowPostMapper knowPostMapper;
    private final ObjectMapper objectMapper;
    private final OssStorageService ossStorageService;

    private final OutboxMapper outboxMapper;
    private final FeedCacheService feedCacheService;
    private final RelationService relationService;
    private final CounterService counterService;


    // 创建草稿（无内容）
    @Override
    @Transactional
    public long createDraft(long creatorId){
        long id = idGen.nextId();
        Instant now = Instant.now();
        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .status("draft")
                .type("image_text")
                .visible("public")
                .isTop(false)
                .createTime(now)
                .updateTime(now)
                .build();
        knowPostMapper.insertDraft(post);

        writeFeedEvent(id, PostProjectionEvent.UPSERT);

        return id;
    }

    @Override
    @Transactional
    public void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256) {
        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .contentObjectKey(objectKey)
                .contentEtag(etag)
                .contentSize(size)
                .contentSha256(sha256)
                .contentUrl(ossStorageService.publicUrl(objectKey))
                .updateTime(Instant.now())
                .build();

        int updated = knowPostMapper.updateContent(post);
        if (updated == 0) {
            throw new RuntimeException( "草稿不存在或无权限");
        }
        writeFeedEvent(id, PostProjectionEvent.UPSERT);
    }

    @Override
    @Transactional
    public void updateMetadata(long creatorId, long id, KnowPostPatchRequest request) {


        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .title(request.title())
                .tagId(request.tagId())
                .tags((toJsonOrNull(request.tags())))
                .imgUrls(toJsonOrNull(request.imgUrls()))
                .visible(request.visible())
                .isTop(request.isTop())
                .description(request.description())
                .type("image_text")
                .updateTime(Instant.now())
                .build();

        // update会帮你判断知文是否存在
        int updated = knowPostMapper.updateMetadata(post);

        if (updated == 0) {
            throw new RuntimeException("草稿不存在或无权限");
        }
        writeFeedEvent(id, PostProjectionEvent.UPSERT);
    }

    @Override
    @Transactional
    public void publish(long creatorId, long id) {
        int updated = knowPostMapper.publish(id,creatorId);
        if(updated == 0){
            throw new RuntimeException("草稿不存在或无权限");
        }

        writeFeedEvent(id, PostProjectionEvent.UPSERT);
    }

    @Override
    @Transactional
    public void updateTop(long creatorId, long id, Boolean isTop) {
        int updated = knowPostMapper.updateTop(id, creatorId, isTop);
        if(updated == 0){
            throw new RuntimeException("草稿不存在或无权限");
        }
        writeFeedEvent(id, PostProjectionEvent.UPSERT);
    }

    @Override
    @Transactional
    public void updateVisibility(long creatorId, long id, String visible) {
        if (!isValidVisible(visible)) {
            throw new RuntimeException("可见性取值非法");
        }
        int updated = knowPostMapper.updateVisibility(id, creatorId, visible);
        if(updated == 0){
            throw new RuntimeException("草稿不存在或无权限");
        }
        writeFeedEvent(id, PostProjectionEvent.UPSERT);
    }

    @Override
    @Transactional
    public void delete(long creatorId, long id) {
        int deleted = knowPostMapper.softDelete(id, creatorId);
        if(deleted == 0){
            throw new RuntimeException("草稿不存在或无权限");
        }
        writeFeedEvent(id, PostProjectionEvent.DELETE);
    }

    @Override
    @Transactional
    public KnowPostDetailResponse getDetail(long id, Long currentUserIdNullable) {
        KnowPostDetailRow row = knowPostMapper.findDetailById(id);
        // 不存在或已删除
        if(row == null || row.getStatus().equals("deleted")){
            throw new RuntimeException("内容不存在");
        }
        // 权限校验
        // 公开策略：已发布且公开可见
        // 私有策略：作者本人查看
        boolean owner = currentUserIdNullable != null
                && currentUserIdNullable.equals(row.getCreatorId());

        boolean publicPost =
                "published".equals(row.getStatus())
                        && "public".equals(row.getVisible());

        boolean followerPost =
                currentUserIdNullable != null
                        && "published".equals(row.getStatus())
                        && "followers".equals(row.getVisible())
                        && relationService.isFollowing(
                        currentUserIdNullable,
                        row.getCreatorId()
                );

        if (!owner && !publicPost && !followerPost) {
            throw new RuntimeException("无权限查看");
        }

        // 解析图片和标签 JSON
        List<String> images = parseStringArray(row.getImgUrls());
        List<String> tags = parseStringArray(row.getTags());

        long likes = counterService.getCount(id, CounterType.LIKE);
        long favorites = counterService.getCount(id, CounterType.FAV);

        boolean liked = currentUserIdNullable != null
                && counterService.isLiked(
                CounterType.LIKE,
                id,
                currentUserIdNullable
        );

        boolean favorited = currentUserIdNullable != null
                && counterService.isLiked(
                CounterType.FAV,
                id,
                currentUserIdNullable
        );

        writeFeedEvent(id, PostProjectionEvent.UPSERT);

        return new KnowPostDetailResponse(
                String.valueOf(row.getId()),
                row.getTitle(),
                row.getDescription(),
                row.getContentUrl(),
                images,
                tags,
                String.valueOf(row.getCreatorId()),
                row.getAuthorAvatar(),
                row.getAuthorNickname(),
                row.getAuthorTagJson(),
                likes,
                favorites,
                liked,
                favorited,
                row.getIsTop(),
                row.getVisible(),
                row.getType(),
                row.getPublishTime()
        );

    }


    // objectMapper方法受检异常封装为函数
    private String toJsonOrNull(List<String> list) {
        if (list == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 处理失败");
        }
    }

    // 将Json转化为lsit
    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // 可见性必须合法取值
    private boolean isValidVisible(String visible) {
        if (visible == null) {
            return false;
        }

        return switch (visible) {
            case "public", "followers", "school", "private", "unlisted" -> true;
            default -> false;
        };
    }

    private void writeFeedEvent(
            long postId,
            String operation
    ) {
        PostProjectionEvent event =
                PostProjectionEvent.DELETE.equals(operation)
                        ? PostProjectionEvent.delete(postId)
                        : PostProjectionEvent.upsert(postId);

        try {
            outboxMapper.insert(
                    OutboxTopics.AGG_KNOWPOST,
                    postId,
                    operation,
                    objectMapper.writeValueAsString(event)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "帖子 Feed 事件序列化失败",
                    exception
            );
        }

        /*
         * 立即删除旧片段，防止可见性变更后短时间读到旧卡片。
         * Outbox 消费者负责最终重建。
         */
        feedCacheService.evictPost(postId);
    }



}
