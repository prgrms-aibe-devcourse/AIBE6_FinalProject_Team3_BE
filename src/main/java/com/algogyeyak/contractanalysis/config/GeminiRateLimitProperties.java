package com.algogyeyak.contractanalysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 무료 티어 호출 한도를 서버가 사전 방어하기 위한 정책값(gemini.rate-limit.*).
 * Gemini 쪽 실제 한도가 바뀌거나 유료 티어로 전환될 때 재배포 없이 튜닝할 수 있도록
 * MarketComparisonProperties와 동일한 방식(record + @ConfigurationProperties)으로 뺐다.
 *
 * @Component를 붙이지 않는 이유도 MarketComparisonProperties와 동일 - AlgogyeyakApplication의
 * @ConfigurationPropertiesScan이 대신 등록해준다.
 */
@ConfigurationProperties(prefix = "gemini.rate-limit")
public record GeminiRateLimitProperties(
        int perMinuteLimit,
        int perDayLimit,
        int minIntervalSeconds
) {
}
