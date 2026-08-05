package com.algogyeyak.riskanalysis.controller;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.riskanalysis.dto.RiskSignalResponse;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.service.FakeListingSignalService;
import com.algogyeyak.testsupport.CsrfHeaderMockMvcCustomizer;
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
import org.springframework.context.annotation.Import;
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
@Import(CsrfHeaderMockMvcCustomizer.class)
@DisplayName("RiskAnalysisController")
class RiskAnalysisControllerTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private FakeListingSignalService fakeListingSignalService;

    @MockitoBean
    private UserRepository userRepository;

    // 이 테스트는 위험 신호 분석/조회 로직만 검증하고 blacklist 자체는 다루지 않으므로, 실제 Redis
    // 대신 mock으로 대체한다(mock 기본값 false = "블랙리스트에 없음"이라 정상 인증 흐름을 그대로 탄다).
    @MockitoBean
    private AccessTokenRevocationService accessTokenRevocationService;

    private String tokenFor(Long userId) {
        return jwtProvider.createAccessToken(userId, "test@example.com", Role.USER);
    }

    // JwtAuthenticationFilter가 매 요청 DB에서 유저 상태/권한을 재확인하므로, 토큰 주체(USER_ID)에
    // 대한 활성 유저 스텁이 없으면 인증 자체가 401로 실패한다 - AdminStatsControllerTest와 동일.
    @BeforeEach
    void stubAuthenticatedUserForFilter() {
        User user = User.createLocalUser("test@example.com", "hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
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
