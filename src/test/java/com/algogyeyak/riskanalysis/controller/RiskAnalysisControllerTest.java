package com.algogyeyak.riskanalysis.controller;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.riskanalysis.dto.RiskAnalysisSummaryResponse;
import com.algogyeyak.riskanalysis.dto.RiskSignalResponse;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.service.FakeListingSignalService;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

    // JwtAuthenticationFilter가 인증 시마다 AccessTokenRevocationService.isRevoked()로 Redis를
    // 조회하는데, 로컬에 Redis가 없어도 이 컨트롤러 테스트가 돌아가도록 목으로 대체한다
    // (Mockito 기본값이 false라 별도 스텁 없이도 "무효화 안 됨"으로 처리됨).
    @MockitoBean
    private AccessTokenRevocationService accessTokenRevocationService;

    @MockitoBean
    private UserRepository userRepository;

    // JwtAuthenticationFilter가 매 요청 DB에서 유저 상태/권한을 재확인하므로, 이 스텁이 없으면 이
    // 클래스의 모든 인증된 요청이 401로 실패한다 - 이 파일의 테스트는 전부 userId=1L을 공유한다.
    @BeforeEach
    void stubAuthenticatedUserForFilter() {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    }

    private String tokenFor(Long userId) {
        return jwtProvider.createAccessToken(userId, "test@example.com", Role.USER);
    }

    @Test
    @DisplayName("인증된 사용자가 위험 신호 분석을 요청하면 실행하고 신호 개수 요약을 반환한다")
    void triggerRiskAnalysisReturnsSummary() throws Exception {
        String token = tokenFor(1L);
        RiskAnalysisSummaryResponse summary = new RiskAnalysisSummaryResponse(2, "v1.0", LocalDateTime.now());
        when(fakeListingSignalService.checkAndSummarize(1L, 10L)).thenReturn(summary);

        mockMvc.perform(post("/properties/10/risk-analysis")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.signalCount").value(2))
                .andExpect(jsonPath("$.data.policyVersion").value("v1.0"));

        verify(fakeListingSignalService).checkAndSummarize(eq(1L), eq(10L));
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
