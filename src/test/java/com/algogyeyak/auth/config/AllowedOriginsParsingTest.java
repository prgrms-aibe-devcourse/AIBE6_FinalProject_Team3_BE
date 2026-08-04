package com.algogyeyak.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
@TestPropertySource(properties = "app.cors.allowed-origins=https://a.example.com, https://b.example.com")
class AllowedOriginsParsingTest {

    private static final String UNTRIMMED_ORIGIN = "https://b.example.com";

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
}
