package com.zhiguang.feed.event;

import java.util.UUID;

public record PostProjectionEvent(
        String eventId,
        long postId,
        String op,
        long ts
) {

    public static final String UPSERT = "UPSERT";
    public static final String DELETE = "DELETE";

    public static PostProjectionEvent upsert(long postId) {
        return new PostProjectionEvent(
                UUID.randomUUID().toString(),
                postId,
                UPSERT,
                System.currentTimeMillis()
        );
    }

    public static PostProjectionEvent delete(long postId) {
        return new PostProjectionEvent(
                UUID.randomUUID().toString(),
                postId,
                DELETE,
                System.currentTimeMillis()
        );
    }
}