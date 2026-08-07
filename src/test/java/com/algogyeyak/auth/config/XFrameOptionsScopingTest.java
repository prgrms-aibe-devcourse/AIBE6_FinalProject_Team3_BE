package com.algogyeyak.auth.config;

import com.algogyeyak.testsupport.CsrfHeaderMockMvcCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * 회귀 테스트 - SecurityConfig의 X-Frame-Options 완화가 "H2 콘솔 경로에 한해"라는 주석과 달리
 * 이전에는 headers().frameOptions()로 응답 전체에 SAMEORIGIN을 적용했다. 일반 API 응답은 기본값
 * DENY를 유지하고, /h2-console/**만 SAMEORIGIN을 받는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CsrfHeaderMockMvcCustomizer.class)
class XFrameOptionsScopingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void regularApiResponseKeepsDefaultDenyFrameOptions() throws Exception {
        mockMvc.perform(get("/auth/password-policy"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void h2ConsolePathGetsRelaxedSameOriginFrameOptions() throws Exception {
        mockMvc.perform(get("/h2-console"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }
}
