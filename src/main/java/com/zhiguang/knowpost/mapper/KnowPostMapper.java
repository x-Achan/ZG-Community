package com.zhiguang.knowpost.mapper;

import com.zhiguang.knowpost.model.KnowPost;
import com.zhiguang.knowpost.model.KnowPostDetailRow;

import com.zhiguang.knowpost.model.KnowPostFeedRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowPostMapper {

    // 插入草稿
    void insertDraft(KnowPost post);

    // 根据id返回知文
    KnowPost findById(@Param("id") Long id);

    // 根据文章id和作者id更新文章内容
    int updateContent(KnowPost post);
    // 更改文章元数据，不改文章内容
    int updateMetadata(KnowPost post);


    // 状态更新：修改 status 状态为 published 已发布
    int publish(@Param("id") Long id, @Param("creatorId") Long creatorId);
    // 状态更新：设置置顶 isTop
    int updateTop(@Param("id") Long id, @Param("creatorId") Long creatorId, @Param("isTop") Boolean isTop);
    // 状态更新：设置可见性 visible
    int updateVisibility(@Param("id") Long id, @Param("creatorId") Long creatorId, @Param("visible") String visible);
    // 状态更新：修改 status 状态为 deleted 软删除
    int softDelete(@Param("id") Long id, @Param("creatorId") Long creatorId);



    // 首页 Feed 列表（已发布、公开可见），置顶优先，其次按发布时间倒序。
    List<KnowPostFeedRow> listFeedPublic(@Param("limit") int limit,
                                         @Param("offset") int offset);
    // 我的知文列表（当前用户已发布内容），置顶优先，其次按发布时间倒序。
    List<KnowPostFeedRow> listMyPublished(@Param("creatorId") long creatorId,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);



    // 详情查询（含作者信息）
    KnowPostDetailRow findDetailById(@Param("id") Long id);

    // 统计我的已发布知文数量
    long countMyPublished(@Param("creatorId") long creatorId);

    // 列出我的已发布知文ID列表
    List<Long> listMyPublishedIds(@Param("creatorId") long creatorId);



    /** 计数增减（delta 可正可负），GREATEST 保证不为负 */
    int incrementLikeCount(@Param("postId") long postId, @Param("delta") int delta);

    int incrementFavCount(@Param("postId") long postId, @Param("delta") int delta);

    /** 读回权威计数 */
    Long findLikeCount(@Param("postId") long postId);

    Long findFavCount(@Param("postId") long postId);

    /**
     * 查询单个 Feed 卡片。
     */
    KnowPostFeedRow findFeedRowById(@Param("id") long id);

    /**
     * 批量查询 Feed 卡片，消除 N+1。
     */
    List<KnowPostFeedRow> findFeedRowsByIds(
            @Param("ids") List<Long> ids
    );

    /**
     * 作者主页作品更新后，重建作者主页索引。
     * 包含草稿和已发布内容，排除 deleted。
     */
    List<KnowPost> listAuthorPostsForFeedIndex(
            @Param("creatorId") long creatorId
    );

    /**
     * 重建热门候选池。
     */
    List<Long> listPublicHotCandidateIds(
            @Param("fromTime") java.time.Instant fromTime,
            @Param("limit") int limit
    );

    /**
     * 关注普通作者时，回填作者最近发布的帖子。
     */
    List<KnowPostFeedRow> listRecentVisiblePostsByAuthor(
            @Param("creatorId") long creatorId,
            @Param("limit") int limit
    );

    /**
     * 关注 Feed inbox 丢失时，从 MySQL 重建最近帖子。当前用户关注的人"的最近帖子
     */
    List<KnowPostFeedRow> listRecentFollowingPosts(
            @Param("userId") long userId,
            @Param("limit") int limit
    );
}