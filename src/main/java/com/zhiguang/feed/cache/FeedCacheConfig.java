package com.zhiguang.feed.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhiguang.feed.model.FeedPageSnapshot;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class FeedCacheConfig {
    @Bean
    public Cache<String, FeedPageSnapshot> feedPageLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build();
    }
}