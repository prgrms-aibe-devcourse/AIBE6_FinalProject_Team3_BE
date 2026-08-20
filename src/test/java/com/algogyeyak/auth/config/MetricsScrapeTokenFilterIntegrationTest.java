package com.algogyeyak.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회귀 테스트(2026-08-20 전수조사) - MetricsScrapeTokenFilterTest는 필터를 직접 new해서 단위로만
// 검증하므로, SecurityConfig.filterChain()에 실제로 등록됐는지는 전혀 확인하지 못한다 - 그 등록
// 줄을 통째로 지워도 그 6개 단위테스트는 전부 그대로 통과한다. 이 테스트는 실제 필터체인 전체를
// 띄워 /actuator/prometheus가 애플리케이션 레벨에서 실제로 막혀 있는지 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MetricsScrapeTokenFilterIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // application.yml의 로컬 기본값(METRICS_SCRAPE_TOKEN 환경변수 미설정 시) - monitoring/metrics_scrape_token
    // 파일과도 같은 값이어야 한다.
    private static final String LOCAL_DEFAULT_TOKEN = "local-dev-only-scrape-token-please-override";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpointRejectsRequestsWithoutScrapeToken() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusEndpointAcceptsRequestsWithCorrectScrapeToken() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + LOCAL_DEFAULT_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void healthEndpointRemainsPublicWithoutAnyToken() throws Exception {
        // health는 MetricsScrapeTokenFilter의 대상이 아니다(로드밸런서/업타임 모니터링이 인증 없이
        // 호출해야 함) - prometheus만 막고 health까지 같이 막아버리는 회귀를 방지한다. 200을
        // 단언하지 않는 이유 - 이 테스트 환경엔 실제 SMTP 서버가 없어 mail 헬스 인디케이터가
        // DOWN이라 실제로는 503이 나갈 수 있다(그 자체는 이 필터와 무관한 별개 관심사). 여기서
        // 검증하려는 건 오직 "이 필터가 health를 막지 않는다"는 것뿐이므로, 필터가 관여했다는
        // 신호인 401만 아니면 된다.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401) {
                        throw new AssertionError(
                                "health 엔드포인트가 MetricsScrapeTokenFilter에 막혔다(401) - health는 이 필터의 대상이 아니어야 한다.");
                    }
                });
    }
}
