package com.zhiguang.auth.verification;

import com.zhiguang.auth.model.VerificationScene;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 开发/测试用验证码发送器。
 * <p>
 * 不实际发送，仅记录日志，便于本地开发与集成测试。
 */
@Slf4j
@Component
public class CodeSender {

    /** 记录验证码发送信息到日志（不实际发送）*/
    public void sendCode(VerificationScene scene, String identifier, String code, int expireMinutes) {
        System.out.println("sendCode 被调用了");
        log.info("Send verification code scene={} identifier={} code={} expireMinutes={}", scene, identifier, code, expireMinutes);
    }
}
