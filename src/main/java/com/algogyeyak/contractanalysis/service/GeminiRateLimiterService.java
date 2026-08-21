package com.algogyeyak.contractanalysis.service;

import com.algogyeyak.contractanalysis.config.GeminiRateLimitProperties;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Gemini 무료 티어 호출 한도(분당/일별)를 서버가 먼저 방어하기 위한 Redis 기반 사전 체크.
 * ContractAnalysisController의 Gemini 호출 경로(analyze/chat) 진입 시점에 호출한다.
 *
 * Redis 장애 시에는 AccessTokenRevocationService(fail-closed)와 반대로 fail-open으로
 * 처리한다 - 이 카운터는 인증/보안 경계가 아니라 외부 API 과금·쿼터 방어용 안전장치라,
 * Redis가 잠깐 죽었다고 계약 분석 기능 자체를 막는 것은 과한 대가다. 카운터를 못 세는 동안은
 * Gemini 자체 쿼터 초과 응답에 기대는 정도로 감수한다.
 */
@Slf4j
@Service
public class GeminiRateLimiterService {

    private static final DateTimeFormatter DAY_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration MINUTE_TTL = Duration.ofSeconds(60);
    private static final Duration DAY_TTL = Duration.ofSeconds(86400);
    private static final String LAST_CALL_MARKER = "1";

    private final StringRedisTemplate redisTemplate;
    private final GeminiRateLimitProperties properties;

    public GeminiRateLimiterService(StringRedisTemplate redisTemplate, GeminiRateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void checkAndConsume(Long userId) {
        try {
            // 유저별 최소 호출 간격을 분당/일별 공유 카운터보다 먼저 체크한다 - 순서를 반대로 하면
            // 한 유저가 짧은 간격으로 반복 호출할 때마다 공유 카운터부터 먼저 소모된 뒤에야
            // 차단되어, "공유 한도를 한 명이 독점하지 못하게 한다"는 목적 자체가 무력화된다.
            checkUserInterval(userId);
            checkAndIncrement("ai:gemini:count:minute:" + currentEpochMinute(), MINUTE_TTL, properties.perMinuteLimit());
            checkAndIncrement("ai:gemini:count:day:" + currentDayKey(), DAY_TTL, properties.perDayLimit());
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 Gemini 호출 한도 체크를 건너뜁니다(fail-open) - userId={}", userId, e);
        }
    }

    // SET NX EX 한 번으로 "값이 없으면 설정하고 성공(true), 있으면 실패(false)"가 원자적으로
    // 처리되므로 GET 후 SET 하는 방식과 달리 동시 요청 사이의 race가 없다.
    private void checkUserInterval(Long userId) {
        String key = "ai:gemini:user:" + userId + ":lastCall";
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, LAST_CALL_MARKER, Duration.ofSeconds(properties.minIntervalSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RATE_LIMITED);
        }
    }

    // INCR로 카운터를 올린 뒤, 그 결과가 1(=이 윈도우의 첫 요청이라 방금 키가 새로 생성됨)일 때만
    // EXPIRE를 건다 - 매 호출마다 TTL을 다시 걸면 요청이 계속 들어오는 한 윈도우가 끝없이 밀려서
    // "분당/일별" 고정 윈도우라는 전제가 깨진다.
    private void checkAndIncrement(String key, Duration ttl, int limit) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, ttl);
        }
        if (count != null && count > limit) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RATE_LIMITED);
        }
    }

    private long currentEpochMinute() {
        return System.currentTimeMillis() / 60_000;
    }

    private String currentDayKey() {
        return LocalDate.now().format(DAY_KEY_FORMAT);
    }
}
