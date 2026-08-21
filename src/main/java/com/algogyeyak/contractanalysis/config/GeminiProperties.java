package com.algogyeyak.contractanalysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini API 호출에 필요한 인증/모델 설정값(gemini.*). ClovaOcrClientImpl 등 다른 외부 API
 * 클라이언트와 마찬가지로 GeminiRateLimitProperties와 동일한 방식(record + @ConfigurationProperties)으로 뺐다.
 *
 * @Component를 붙이지 않는 이유도 GeminiRateLimitProperties/MarketComparisonProperties와 동일 -
 * AlgogyeyakApplication의 @ConfigurationPropertiesScan이 대신 등록해준다.
 */
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        String apiKey,
        String model
) {
}
