package com.zhiguang.auth.verification;

import com.zhiguang.auth.config.AuthProperties;
import com.zhiguang.auth.model.VerificationScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class VerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final RedisVerificationCodeStore codeStore;
    private final CodeSender codeSender;
    private final AuthProperties properties;

    public SendCodeResult sendCode(VerificationScene scene, String identifier){
        if (scene == null || !StringUtils.hasText(identifier)) {
            throw new RuntimeException("请提供正确的验证码发送参数");
        }
        AuthProperties.Verification cfg = properties.getVerification();
        String code = generateNumericCode(cfg.getCodeLength());
        // Redis存储：验证码、时间间隔限制、每日发送上限
        codeStore.saveCode(scene.name(), identifier, code);
        codeStore.enforceSendInterval(scene, identifier);
        codeStore.enforceDailyLimit(scene, identifier);
        codeSender.sendCode(scene, identifier, code, (int) cfg.getTtl().toMinutes());
        return new SendCodeResult(identifier, scene, (int) cfg.getTtl().toSeconds());
    }

    public VerificationCheckResult verify(VerificationScene scene, String identifier, String code) {
        if (scene == null || !StringUtils.hasText(identifier) || !StringUtils.hasText(code)) {
            throw new RuntimeException("验证码校验参数不完整");
        }
        return codeStore.verify(scene.name(), identifier, code);
    }

    /**生成指定长度的纯数字验证码 */
    private static String generateNumericCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
