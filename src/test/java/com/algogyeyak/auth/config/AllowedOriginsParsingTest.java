package com.algogyeyak.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * app.cors.allowed-origins가 "https://a.com, https://b.com"처럼 콤마 뒤에 공백을 두고 설정돼도
 * (흔한 표기), CORS(corsConfigurationSource)와 CsrfHeaderFilter의 Origin 폴백이 둘 다 그 공백을
 * 무시하고 같은 origin으로 인식하는지 확인한다 - 예전엔 CsrfHeaderFilter만 trim해서, CORS 쪽이
 * " https://b.com"(공백 포함, 매칭 실패)으로 등록되면 실제 브라우저 요청이 CORS 단계에서부터
 * 막혀 CsrfHeaderFilter까지 가지도 못하는 채로 "Failed to fetch"만 보이는 문제가 있었다.
 *
 * CsrfHeaderMockMvcCustomizer를 일부러 @Import하지 않는다 - 이 테스트는 정확히 "커스텀 헤더 없이
 * Origin만으로" CSRF 필터를 통과하는지 확인해야 하는데, 그 커스텀 커스터마이저가 헤더를 자동으로
 * 붙이면 Origin 폴백 로직 자체가 맞는지와 무관하게 항상 통과해버려 테스트가 무의미해진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "app.cors.allowed-origins=https://a.example.com, https://b.example.com, https://c.example.com/")
class AllowedOriginsParsingTest {

    private static final String UNTRIMMED_ORIGIN = "https://b.example.com";
    // 설정값 쪽에만 트레일링 슬래시가 붙어 있고("https://c.example.com/"), 실제 브라우저가 보내는
    // Origin 헤더는 절대 슬래시를 붙이지 않는다 - 슬래시를 벗겨내지 않으면 둘이 영원히 매칭되지
    // 않는다.
    private static final String TRAILING_SLASH_CONFIGURED_ORIGIN = "https://c.example.com";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void corsReflectsTrimmedOriginInResponseHeader() throws Exception {
        mockMvc.perform(post("/auth/logout").header("Origin", UNTRIMMED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Origin", UNTRIMMED_ORIGIN));
    }

    @Test
    void csrfOriginFallbackAcceptsSameTrimmedOriginWithoutCustomHeader() throws Exception {
        mockMvc.perform(post("/auth/logout").header("Origin", UNTRIMMED_ORIGIN))
                .andExpect(status().isOk());
    }

    @Test
    void corsAndCsrfIgnoreTrailingSlashInConfiguredOrigin() throws Exception {
        mockMvc.perform(post("/auth/logout").header("Origin", TRAILING_SLASH_CONFIGURED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", TRAILING_SLASH_CONFIGURED_ORIGIN));
    }

    // CsrfHeaderMockMvcCustomizer를 이 클래스 전체가 일부러 @Import하지 않으므로(클래스 주석 참고),
    // 아래 두 테스트는 실제로 CsrfHeaderFilter가 요청을 막는 경로 자체를 처음으로 검증한다 - 이전엔
    // hasValidCsrfSignal()의 조건이 실수로 뒤집혀도(예: 항상 true) 테스트 스위트 전체가 그린으로
    // 남을 수 있었다.
    @Test
    void csrfRejectsRequestWithoutOriginOrCustomHeader() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_HEADER_MISSING"));
    }

    // 허용되지 않은 Origin은 CsrfHeaderFilter가 아니라 그보다 먼저 도는 Spring의 CorsFilter가
    // 이미 막는다(둘 다 parseAllowedOrigins()를 공유하므로, CsrfHeaderFilter의 Origin 폴백이
    // "막아야 할" origin을 실제로 볼 일은 없다) - 그래서 응답이 우리 ApiResponse 포맷이 아니라
    // Spring 내장 CorsFilter의 평문 메시지다. 방어선이 CsrfHeaderFilter가 아니어도 최종적으로
    // 막히는지가 중요하므로 그 사실 자체를 검증한다.
    @Test
    void csrfRejectsRequestWithDisallowedOrigin() throws Exception {
        mockMvc.perform(post("/auth/logout").header("Origin", "https://evil.example.com"))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Invalid CORS request"));
    }
}
