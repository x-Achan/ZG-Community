package com.zhiguang.relation.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessageParser {

    private final ObjectMapper objectMapper;

    public List<String> extractPayloads(
            String canalMessage,
            String expectedAggregateType
    ) {
        try {
            JsonNode root = objectMapper.readTree(canalMessage);

            if (root.path("isDdl").asBoolean(false)
                    || !"outbox".equalsIgnoreCase(root.path("table").asText())
                    || !"INSERT".equalsIgnoreCase(root.path("type").asText())) {
                return List.of();
            }

            JsonNode rows = root.path("data");
            if (!rows.isArray()) {
                return List.of();
            }

            List<String> payloads = new ArrayList<>();

            for (JsonNode row : rows) {
                if (!expectedAggregateType.equals(
                        row.path("aggregate_type").asText())) {
                    continue;
                }

                String payload = row.path("payload").asText();
                if (!payload.isBlank()) {
                    payloads.add(payload);
                }
            }

            return payloads;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Canal 消息格式错误",
                    exception
            );
        }
    }

    public List<String> extractRelationPayloads(String message) {
        return extractPayloads(
                message,
                OutboxTopics.AGG_FOLLOWING
        );
    }
}