package com.zhiguang.knowpost.model;

import lombok.Data;

import java.time.Instant;

// 数据库查询返回 KnowPostFeedRow
@Data
public class KnowPostFeedRow {

    private Long id;
    private Long creatorId;

    private String title;
    private String description;
    private String tags;
    private String imgUrls;

    private String authorAvatar;
    private String authorNickname;
    private String authorTagJson;

    private String status;
    private String visible;
    private String type;

    private Boolean isTop;
    private Instant createTime;
    private Instant publishTime;
}
