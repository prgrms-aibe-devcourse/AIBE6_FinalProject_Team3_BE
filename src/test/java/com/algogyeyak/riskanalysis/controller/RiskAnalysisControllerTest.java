package com.algogyeyak.riskanalysis.controller;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.riskanalysis.dto.RiskSignalResponse;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.service.FakeListingSignalService;
import com.algogyeyak.user.enums.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RiskAnalysisController")
class RiskAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private FakeListingSignalService fakeListingSignalService;

    private String tokenFor(Long userId) {
        return jwtProvider.createAccessToken(userId, "test@example.com", Role.USER);
    }

    @Test
    @DisplayName("인증된 사용자가 위험 신호 분석을 요청하면 실행하고 성공 응답을 반환한다")
    void triggerRiskAnalysisReturnsSuccess() throws Exception {
        String token = tokenFor(1L);

        mockMvc.perform(post("/properties/10/risk-analysis")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(fakeListingSignalService).checkAndSave(eq(1L), eq(10L));
    }

    @Test
    @DisplayName("인증된 사용자가 위험 신호 목록을 조회하면 신호 목록을 반환한다")
    void getRiskSignalsReturnsSignalList() throws Exception {
        String token = tokenFor(1L);
        RiskSignalResponse signal = new RiskSignalResponse(
                RiskSignalType.DUPLICATE_LISTING, RiskCheckStatus.SUCCESS, null,
                "동일 주소로 등록된 다른 매물이 있어요", LocalDateTime.now());
        when(fakeListingSignalService.getSignals(1L, 10L)).thenReturn(List.of(signal));

        mockMvc.perform(get("/properties/10/risk-signals")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].signalType").value("DUPLICATE_LISTING"))
                .andExpect(jsonPath("$.data[0].description").value("동일 주소로 등록된 다른 매물이 있어요"));
    }
}
