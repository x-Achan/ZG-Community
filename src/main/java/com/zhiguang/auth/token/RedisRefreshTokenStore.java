package com.zhiguang.auth.token;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * 基于 Redis 的刷新令牌白名单存储。
 * <p>
 * 键空间：`auth:rt:{userId}:{tokenId}`，值固定为 "1"，设置 TTL 控制过期。
 * 支持校验令牌有效性、撤销单个令牌或撤销某用户全部令牌。
 */
@Component
public class RedisRefreshTokenStore {

    private final StringRedisTemplate redisTemplate;
    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    /** 1.将刷新令牌写入白名单，设置过期时间 */
    public void storeToken(long userId, String tokenId, Duration ttl) {
        String key = key(userId, tokenId);
        redisTemplate.opsForValue().set(key, "1", ttl);
    }

    /** 2.判断刷新令牌是否仍有效 */
    public boolean isTokenValid(long userId, String tokenId) {
        String key = key(userId, tokenId);
        return Objects.equals("1", redisTemplate.opsForValue().get(key));
    }

    /** 3.撤销单个刷新令牌 */
    public void revokeToken(long userId, String tokenId) {
        redisTemplate.delete(key(userId, tokenId));
    }

    /** 4.撤销该用户全部刷新令牌（所有设备退出）*/
    public void revokeAll(long userId) {
        String pattern = "auth:rt:%d:*".formatted(userId);
        var keys = redisTemplate.keys(pattern);
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
    /** 5.生成白名单键名。*/
    private static String key(long userId, String tokenId) {
        return "auth:rt:%d:%s".formatted(userId, tokenId);
    }
}
