package com.zhiguang.feed.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.feed.service.FeedProjectionService;
import com.zhiguang.relation.event.OutboxMessageParser;
import com.zhiguang.relation.event.OutboxTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostProjectionEventConsumer {

    private final OutboxMessageParser parser;
    private final ObjectMapper objectMapper;
    private final FeedProjectionService projectionService;

    @KafkaListener(topics = "${relation.outbox.topic:canal-outbox}", groupId = "${feed.outbox.consumer-group:feed-projection}")
    public void onMessages(List<String> messages, Acknowledgment acknowledgment) {
        try {
            for (String message : messages) {
                List<String> payloads = parser.extractPayloads(
                        message,
                        OutboxTopics.AGG_KNOWPOST
                );

                for (String payload : payloads) {
                    // 核心，处理事件
                    PostProjectionEvent event = objectMapper.readValue(payload, PostProjectionEvent.class);
                    projectionService.projectPost(event);
                }
            }

            acknowledgment.acknowledge();
        } catch (Exception exception) {
            log.error("Feed 帖子投影失败", exception);
            throw new IllegalStateException(
                    "Feed 帖子投影失败",
                    exception
            );
        }
    }
}