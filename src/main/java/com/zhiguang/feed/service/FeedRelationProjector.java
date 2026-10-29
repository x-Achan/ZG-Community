package com.zhiguang.feed.service;

import com.zhiguang.feed.service.impl.FeedIndexService;
import com.zhiguang.relation.event.RelationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FeedRelationProjector {

    private final FeedIndexService feedIndexService;
// 关注事件接入 Feed
    public void project(RelationEvent event) {
        if (event.isCreated()) {
            feedIndexService.projectRelation(event.fromUserId(), event.toUserId(), true);
        } else if (RelationEvent.TYPE_FOLLOW_CANCELED.equals(event.type())) {
            feedIndexService.projectRelation(event.fromUserId(), event.toUserId(), false);
        }
    }
}