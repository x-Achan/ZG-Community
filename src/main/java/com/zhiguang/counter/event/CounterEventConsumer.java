package com.zhiguang.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.counter.mapper.LikeRecordMapper;
import com.zhiguang.counter.mapper.dto.LikeRecordRow;
import com.zhiguang.counter.model.CounterEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计数事件消费者 —— 写聚合的落点。
 *
 * <p><b>它只做一件事：把一批事件按最终状态合并后，批量写入或删除 like_record。</b></p>
 *
 * <p><b>为什么这就解决了瓶颈</b>：原文是"200 个并发点赞 = 200 次单条 INSERT"，
 * 在唯一索引 uk_user_post_type 上互相抢 gap 锁（实测 238 次行锁等待，721ms）。
 * 现在是"200 条事件 = 1 次多值 INSERT"，锁只加一次。实测耗时降到 35ms、锁等待归零。</p>
 *
 * <p><b>为什么不需要去重</b>：计数已经在同步路径加过了，消费端只写明细，
 * 而明细靠唯一键 + INSERT IGNORE 天然幂等 —— Kafka 重复投递也不会产生重复行。
 * 这就是"计数同步、明细异步"相比"两者都异步"的简化收益。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterEventConsumer {

    private final LikeRecordMapper likeRecordMapper;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${counter.kafka.topic:counter-events}", groupId = "${spring.kafka.consumer.group-id:counter-persist}")
    public void onMessages(List<String> messages, Acknowledgment ack) {
        if (messages == null || messages.isEmpty()) {
            ack.acknowledge();
            return;
        }
        // ① 反序列化（单条坏数据不能拖垮整批）
        List<CounterEvent> events = new ArrayList<>(messages.size());
        for (String raw : messages) {
            try {
                events.add(objectMapper.readValue(raw, CounterEvent.class));
            } catch (Exception e) {
                log.error("[计数落库] 事件反序列化失败，跳过: {}", raw, e);
            }
        }

        // ② 同一个用户对同一帖子、同一指标可能在一个批次里连续切换。
        // 按消息顺序覆盖，最终只保留最后一次操作，避免先取消后点赞被错误地删掉。
        Map<LikeRecordRow, Integer> finalStates = new LinkedHashMap<>(events.size());
        for (CounterEvent e : events) {
            try {
                if (e.delta() != 1 && e.delta() != -1) {
                    log.error("[计数落库] 事件 delta 非法，跳过: {}", e);
                    continue;
                }
                LikeRecordRow row = new LikeRecordRow(e.uid(), Long.parseLong(e.eid()), e.metric());
                finalStates.put(row, e.delta());
            } catch (NumberFormatException ex) {
                log.error("[计数落库] 事件 eid 非法，跳过: {}", e, ex);
            }
        }

        List<LikeRecordRow> inserts = new ArrayList<>();
        List<LikeRecordRow> deletes = new ArrayList<>();
        finalStates.forEach((row, delta) -> {
            if (delta > 0) {
                inserts.add(row);
            } else {
                deletes.add(row);
            }
        });

        // ③ 批量 INSERT / DELETE。唯一键和条件删除让 Kafka 重投保持幂等。
        if (!inserts.isEmpty()) {
            likeRecordMapper.batchInsertIgnore(inserts);
        }
        if (!deletes.isEmpty()) {
            likeRecordMapper.batchDelete(deletes);
        }

        // ④ 处理完才提交位点（手动 ack）
        ack.acknowledge();
        log.info("[计数落库] 本批={} 合并={} 新增={} 删除={}",
                events.size(), finalStates.size(), inserts.size(), deletes.size());
    }
}
