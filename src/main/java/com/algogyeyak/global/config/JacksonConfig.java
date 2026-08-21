package com.algogyeyak.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 공유 ObjectMapper 빈. spring-boot-starter-webmvc(Boot 4의 모듈화된 starter)는 예전
 * spring-boot-starter-web과 달리 JacksonAutoConfiguration이 기본으로 딸려오지 않아서,
 * 이 빈을 등록해두지 않으면 ObjectMapper를 생성자로 주입받으려는 빈(예:
 * ContractAnalysisAnalyzeService)이 "No qualifying bean of type ObjectMapper" 로
 * 기동 자체에 실패한다(RedisCacheConfig의 CacheManager 수동 등록과 같은 이유 - Boot 4가
 * autoconfigure를 모듈별로 쪼개면서 생긴 공백). findAndRegisterModules()로 Java 8
 * 시간 타입(LocalDateTime 등) 지원 모듈을 붙인다 - UserAuthStatusCacheService가 로컬로
 * 만들어 쓰던 것과 동일한 설정.
 *
 * <p>주의: 여러 클래스가 여전히 각자 로컬로 {@code new ObjectMapper()}를 만들어 쓰고 있다
 * (AdminAuditLogger, CsrfHeaderFilter, SecurityConfig, ClovaOcrClientImpl,
 * MetricsScrapeTokenFilter 등) - 이 빈은 그것들을 전부 이 빈으로 교체하는 리팩터링이 아니라,
 * 새로 생성자 주입을 요구하게 된 ContractAnalysisAnalyzeService의 기동 실패만 막기 위한
 * 최소 범위 수정이다. MolitRentClientImpl/MolitTradeClientImpl의 MOLIT_OBJECT_MAPPER는
 * 국토부 API 응답 파싱 전용으로 별도 설정이 있어 이 빈과 무관하게 그대로 둔다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
