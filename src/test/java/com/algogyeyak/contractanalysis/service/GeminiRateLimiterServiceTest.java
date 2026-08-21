package com.algogyeyak.contractanalysis.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.algogyeyak.contractanalysis.config.GeminiRateLimitProperties;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// HTTP 레벨 통합 테스트로는 Redis 장애 시 fail-open을 검증할 수 없다 - 인증이 필요한 엔드포인트라
// JwtAuthenticationFilter의 AccessTokenRevocationService.isRevoked()(fail-closed)가 먼저 Redis를
// 호출해서, 요청이 GeminiRateLimiterService까지 도달하기 전에 인증 단계에서 막힌다. 그래서 여기서는
// AccessTokenRevocationServiceTest와 동일한 방식(순수 단위 테스트, StringRedisTemplate/
// ValueOperations를 Mockito로 mock)으로 직접 검증한다.
class GeminiRateLimiterServiceTest {

    private static final GeminiRateLimitProperties PROPERTIES = new GeminiRateLimitProperties(5, 20, 10);
    private static final Long USER_ID = 1L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final GeminiRateLimiterService geminiRateLimiterService =
            new GeminiRateLimiterService(redisTemplate, PROPERTIES);

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(GeminiRateLimiterService.class)).addAppender(logAppender);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(GeminiRateLimiterService.class)).detachAppender(logAppender);
    }

    @Test
    void passesWhenUnderAllLimits() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertDoesNotThrow(() -> geminiRateLimiterService.checkAndConsume(USER_ID));

        verify(valueOperations).setIfAbsent(eq("ai:gemini:user:1:lastCall"), eq("1"), eq(Duration.ofSeconds(10)));
    }

    @Test
    void blocksWhenPerMinuteLimitExceeded() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(6L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> geminiRateLimiterService.checkAndConsume(USER_ID));
        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_RATE_LIMITED, exception.getErrorCode());
    }

    @Test
    void blocksWhenPerDayLimitExceeded() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        // 분당 카운터는 한도(5) 이내로 통과시키고, 일별 카운터만 한도(20)를 넘기게 한다.
        when(valueOperations.increment(anyString()))
                .thenReturn(3L)
                .thenReturn(21L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> geminiRateLimiterService.checkAndConsume(USER_ID));
        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_RATE_LIMITED, exception.getErrorCode());
    }

    // 유저별 최소 호출 간격 체크가 분당/일별 공유 카운터보다 먼저 실행되어야, 한 유저의 반복
    // 호출이 공유 카운터부터 먼저 소모시키지 못한다(GeminiRateLimiterService.checkAndConsume 주석
    // 참고) - increment가 전혀 호출되지 않았는지까지 함께 검증한다.
    @Test
    void blocksAndSkipsSharedCountersWhenCalledWithinMinInterval() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> geminiRateLimiterService.checkAndConsume(USER_ID));
        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_RATE_LIMITED, exception.getErrorCode());
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void failsOpenAndLogsWarnWhenRedisConnectionFails() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertDoesNotThrow(() -> geminiRateLimiterService.checkAndConsume(USER_ID));
        assertTrue(hasWarnLog());
    }

    @Test
    void failsOpenAndLogsWarnWhenRedisTimesOut() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("redis timeout"));

        assertDoesNotThrow(() -> geminiRateLimiterService.checkAndConsume(USER_ID));
        assertTrue(hasWarnLog());
    }

    private boolean hasWarnLog() {
        return logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN);
    }
}
