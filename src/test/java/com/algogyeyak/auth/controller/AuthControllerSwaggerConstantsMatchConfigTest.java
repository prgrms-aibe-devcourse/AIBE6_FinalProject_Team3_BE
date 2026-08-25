package com.algogyeyak.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * AuthController의 @Operation/@ApiResponse description은 애노테이션 속성이라 컴파일타임 상수만
 * 허용되고, application.yml의 실제 설정값(@Value로 주입되는 EmailVerificationService/
 * PasswordResetService의 필드)을 참조할 수 없다 - 그래서 그 값을 그대로 베껴온 private static
 * final 상수 3개가 AuthController에 별도로 존재한다(해당 필드 선언부 주석 참고, "설정을 바꾸면 이
 * 상수도 같이 바꿀 것"). 사람이 설정만 바꾸고 이 상수를 같이 바꾸는 걸 잊으면 Swagger 문서/429
 * 응답 설명이 실제 동작과 조용히 어긋난다 - 이 테스트는 application.yml을 직접 읽어 그 어긋남을
 * 컴파일이 아니라 테스트 실패로 드러낸다(Spring context 없이 YAML 파일만 읽으므로 Redis/DB 불필요).
 */
class AuthControllerSwaggerConstantsMatchConfigTest {

    private static final Properties CONFIG = loadApplicationYml();

    private static Properties loadApplicationYml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        return factory.getObject();
    }

    private static int constantOf(String fieldName) throws Exception {
        Field field = AuthController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    @Test
    void emailVerificationResendCooldownConstantMatchesConfig() throws Exception {
        assertEquals(
                Integer.parseInt(CONFIG.getProperty("app.email-verification.resend-cooldown-seconds")),
                constantOf("EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS"));
    }

    @Test
    void emailVerificationTicketValidityConstantMatchesConfig() throws Exception {
        long configuredSeconds =
                Long.parseLong(CONFIG.getProperty("app.email-verification.verified-ticket-validity-seconds"));
        assertEquals(configuredSeconds / 60, constantOf("EMAIL_VERIFICATION_TICKET_VALIDITY_MINUTES"));
    }

    @Test
    void passwordResetRequestCooldownConstantMatchesConfig() throws Exception {
        assertEquals(
                Integer.parseInt(CONFIG.getProperty("app.password-reset.request-cooldown-seconds")),
                constantOf("PASSWORD_RESET_REQUEST_COOLDOWN_SECONDS"));
    }
}
