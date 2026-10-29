package com.zhiguang.auth.verification;

import com.zhiguang.auth.config.AuthProperties;;
import com.zhiguang.auth.model.VerificationCodeStatus;
import com.zhiguang.auth.model.VerificationScene;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;


/**
 * 基于 Redis 的验证码存储实现。
 * <p>
 * 使用 Hash 结构保存 `code`、`maxAttempts` 与 `attempts`，TTL 控制有效期。
 * 校验时支持尝试计数与错误状态返回，成功后删除键以防重用。
 */
@Component
public class RedisVerificationCodeStore {

    private static final String FIELD_CODE = "code";
    private static final String FIELD_MAX_ATTEMPTS = "maxAttempts";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final StringRedisTemplate redisTemplate;
    private final AuthProperties properties;

    public RedisVerificationCodeStore(StringRedisTemplate redisTemplate,AuthProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**保存验证码到 Redis Hash，存储多个字段，用 hash 结构，存储并设置 TTL*/
    public void saveCode(String scene, String identifier, String code) {
        String key = "auth:code:%s:%s".formatted(scene, identifier);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        ops.put(key, FIELD_CODE, code);
        ops.put(key, FIELD_MAX_ATTEMPTS, String.valueOf(properties.getVerification().getMaxAttempts()));
        ops.put(key, FIELD_ATTEMPTS, "0");
        redisTemplate.expire(key, properties.getVerification().getTtl());
    }

    /** 校验验证码是否匹配，更新尝试计数并在成功时删除记录。*/
    public VerificationCheckResult verify(String scene, String identifier, String code) {
        String key = "auth:code:%s:%s".formatted(scene, identifier);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        // 从Redis中取出数据
        Map<String, String> data = ops.entries(key);
        if (data.isEmpty()) {
            return new VerificationCheckResult(VerificationCodeStatus.NOT_FOUND, 0, 0);
        }
        String storedCode = data.get(FIELD_CODE);
        int maxAttempts = parseInt(data.get(FIELD_MAX_ATTEMPTS), 5);
        int attempts = parseInt(data.get(FIELD_ATTEMPTS), 0);
        // 超过最大尝试次数
        if (attempts >= maxAttempts) {
            return new VerificationCheckResult(VerificationCodeStatus.TOO_MANY_ATTEMPTS, attempts, maxAttempts);
        }
        // 判断两个验证码是否相同，相同的删除redis记录
        if (Objects.equals(storedCode, code)) {
            redisTemplate.delete(key);
            return new VerificationCheckResult(VerificationCodeStatus.SUCCESS, attempts, maxAttempts);
        }
        // 验证码错误，则将尝试测试+1，存入redis
        int updatedAttempts = attempts + 1;
        ops.put(key, FIELD_ATTEMPTS, String.valueOf(updatedAttempts));
        if (updatedAttempts >= maxAttempts) {
            redisTemplate.expire(key, Duration.ofMinutes(30));
            return new VerificationCheckResult(VerificationCodeStatus.TOO_MANY_ATTEMPTS, updatedAttempts, maxAttempts);
        }
        return new VerificationCheckResult(VerificationCodeStatus.MISMATCH, updatedAttempts, maxAttempts);
    }

    /*** 发送间隔限制：同一标识在指定间隔内只能发送一次 */
    public void enforceSendInterval(VerificationScene scene, String identifier) {
        Duration interval = properties.getVerification().getSendInterval();
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        String key = "auth:code:last:" + scene.name() + ":" + identifier;
        String existing = redisTemplate.opsForValue().get(key);
        if (existing != null) throw new RuntimeException("请60秒后重试");
        redisTemplate.opsForValue().set(key, "1", interval);
    }

    /**每日发送次数限制：超过上限则抛出限额异常 */
    public void enforceDailyLimit(VerificationScene scene, String identifier) {
        int limit = properties.getVerification().getDailyLimit();
        if (limit <= 0) {return;}
        String date = DAY_FORMAT.format(LocalDate.now());
        String key = "auth:code:count:" + scene.name() + ":" + identifier + ":" + date;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofDays(1));
        }
        if (count != null && count > limit) throw new RuntimeException("超过每日上限");
    }

    /** 解析整数字符串，失败返回默认值。*/
    private static int parseInt(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}

