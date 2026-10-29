package com.zhiguang.feed.service;

import com.zhiguang.feed.api.dto.FeedPageResponse;

public interface FeedService {

    FeedPageResponse hot(String cursor, int size, Long viewerId);

    FeedPageResponse mine(long viewerId, String cursor, int size);

    FeedPageResponse profile(long authorId, long viewerId, String cursor, int size);

    FeedPageResponse following(long viewerId, String cursor, int size);
}