package com.zhiguang.knowpost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.counter.service.CounterService;
import com.zhiguang.feed.cache.FeedCacheService;
import com.zhiguang.knowpost.id.SnowflakeIdGenerator;
import com.zhiguang.knowpost.mapper.KnowPostMapper;
import com.zhiguang.knowpost.service.impl.KnowpostServiceImpl;
import com.zhiguang.relation.outbox.OutboxMapper;
import com.zhiguang.relation.service.RelationService;
import com.zhiguang.storage.OssStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowpostServiceImplTest {

    @Mock SnowflakeIdGenerator idGenerator;
    @Mock KnowPostMapper knowPostMapper;
    @Mock ObjectMapper objectMapper;
    @Mock OssStorageService ossStorageService;
    @Mock OutboxMapper outboxMapper;
    @Mock FeedCacheService feedCacheService;
    @Mock RelationService relationService;
    @Mock CounterService counterService;

    @InjectMocks KnowpostServiceImpl service;

    @Test
    void updateTopPassesIdBeforeCreatorId() {
        when(knowPostMapper.updateTop(200L, 100L, true)).thenReturn(1);

        service.updateTop(100L, 200L, true);

        verify(knowPostMapper).updateTop(200L, 100L, true);
    }

    @Test
    void updateVisibilityPassesIdBeforeCreatorId() {
        when(knowPostMapper.updateVisibility(200L, 100L, "private")).thenReturn(1);

        service.updateVisibility(100L, 200L, "private");

        verify(knowPostMapper).updateVisibility(200L, 100L, "private");
    }
}
