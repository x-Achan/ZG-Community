package com.zhiguang.counter.service;

import com.zhiguang.counter.api.dto.CounterActionResponse;
import com.zhiguang.counter.model.CounterType;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface CounterService {

    CounterActionResponse toggle(CounterType type, long postId, long userId, boolean on);

    long getCount(long postId, CounterType type);

    long rebuild(long postId, CounterType type);

    Map<Long, Long> getCounts(List<Long> postIds, CounterType type);

    Set<Long> getActionPostIds(List<Long> postIds, CounterType type, long userId);

    boolean isLiked(CounterType type, long postId, long userId);


}
