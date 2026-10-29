package com.zhiguang.counter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.counter.event.CounterEventConsumer;
import com.zhiguang.counter.mapper.LikeRecordMapper;
import com.zhiguang.counter.mapper.dto.LikeRecordRow;
import com.zhiguang.counter.model.CounterEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CounterEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LikeRecordMapper mapper = mock(LikeRecordMapper.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final CounterEventConsumer consumer = new CounterEventConsumer(mapper, objectMapper);

    @Test
    void keepsOnlyTheLastStateForEachUserPostAndMetric() throws Exception {
        CounterEvent like = CounterEvent.of("knowpost", "20", com.zhiguang.counter.model.CounterType.LIKE, 10L, 1);
        CounterEvent unlike = CounterEvent.of("knowpost", "20", com.zhiguang.counter.model.CounterType.LIKE, 10L, -1);
        CounterEvent favorite = CounterEvent.of("knowpost", "20", com.zhiguang.counter.model.CounterType.FAV, 10L, 1);

        consumer.onMessages(List.of(
                objectMapper.writeValueAsString(like),
                objectMapper.writeValueAsString(unlike),
                objectMapper.writeValueAsString(favorite)
        ), acknowledgment);

        verify(mapper).batchDelete(List.of(new LikeRecordRow(10L, 20L, "like")));
        verify(mapper).batchInsertIgnore(List.of(new LikeRecordRow(10L, 20L, "fav")));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void laterLikeWinsWhenUnlikeAndLikeAreInTheSameBatch() throws Exception {
        CounterEvent unlike = CounterEvent.of("knowpost", "20", com.zhiguang.counter.model.CounterType.LIKE, 10L, -1);
        CounterEvent like = CounterEvent.of("knowpost", "20", com.zhiguang.counter.model.CounterType.LIKE, 10L, 1);

        consumer.onMessages(List.of(
                objectMapper.writeValueAsString(unlike),
                objectMapper.writeValueAsString(like)
        ), acknowledgment);

        verify(mapper).batchInsertIgnore(List.of(new LikeRecordRow(10L, 20L, "like")));
        verify(mapper, never()).batchDelete(anyList());
        verify(acknowledgment).acknowledge();
    }
}
