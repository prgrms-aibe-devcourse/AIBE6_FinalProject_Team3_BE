package com.algogyeyak.riskanalysis.controller;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.riskanalysis.dto.DepositSafetyCheckResponse;
import com.algogyeyak.riskanalysis.dto.DepositSafetyRecalculateRequest;
import com.algogyeyak.riskanalysis.enums.DepositSafetyStatus;
import com.algogyeyak.riskanalysis.service.DepositSafetyCheckService;
import com.algogyeyak.testsupport.CsrfHeaderMockMvcCustomizer;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CsrfHeaderMockMvcCustomizer.class)
@DisplayName("DepositSafetyController")
class DepositSafetyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private DepositSafetyCheckService depositSafetyCheckService;

    @MockitoBean
    private AccessTokenRevocationService accessTokenRevocationService;

    @MockitoBean
    private UserRepository userRepository;

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
    @DisplayName("인증된 사용자가 보증금 안전성을 조회하면 저장된 결과를 반환한다")
    void getDepositSafetyReturnsSavedCheck() throws Exception {
        String token = tokenFor(1L);
        DepositSafetyCheckResponse response = new DepositSafetyCheckResponse(
                10L, DepositSafetyStatus.CALCULATED, 82, false, null, null,
                "이 집 전세가율은 82%예요.", java.time.LocalDate.of(2026, 7, 31), 5, 300, null,
                LocalDateTime.now(), DepositSafetyCheckResponse.DISCLAIMER, false, 80, 100, 150);
        when(depositSafetyCheckService.get(1L, 10L)).thenReturn(response);

        mockMvc.perform(get("/properties/10/deposit-safety")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.propertyId").value(10))
                .andExpect(jsonPath("$.data.status").value("CALCULATED"))
                .andExpect(jsonPath("$.data.jeonseRatio").value(82))
                .andExpect(jsonPath("$.data.seniorDepositApplied").value(false))
                .andExpect(jsonPath("$.data.disclaimer").value(DepositSafetyCheckResponse.DISCLAIMER));
    }

    @Test
    @DisplayName("인증된 사용자가 선순위보증금을 반영해 재계산을 요청하면 갱신된 결과를 반환한다")
    void recalculateReturnsUpdatedResult() throws Exception {
        String token = tokenFor(1L);
        DepositSafetyRecalculateRequest request = new DepositSafetyRecalculateRequest(50_000_000L, 10_000_000L);
        DepositSafetyCheckResponse response = new DepositSafetyCheckResponse(
                10L, DepositSafetyStatus.CALCULATED, 87, true, 50_000_000L, 10_000_000L,
                "이 집 전세가율은 87%예요.", java.time.LocalDate.of(2026, 7, 31), 5, 300, null,
                LocalDateTime.now(), DepositSafetyCheckResponse.DISCLAIMER, false, 80, 100, 150);
        when(depositSafetyCheckService.recalculate(1L, 10L, 50_000_000L, 10_000_000L)).thenReturn(response);

        mockMvc.perform(post("/properties/10/deposit-safety/recalculate")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jeonseRatio").value(87))
                .andExpect(jsonPath("$.data.seniorDepositApplied").value(true))
                .andExpect(jsonPath("$.data.seniorDeposit").value(50_000_000))
                .andExpect(jsonPath("$.data.maxClaimAmount").value(10_000_000));
    }

    @Test
    @DisplayName("선순위보증금이 음수면 400 Bad Request를 반환한다")
    void recalculateRejectsNegativeSeniorDeposit() throws Exception {
        String token = tokenFor(1L);
        DepositSafetyRecalculateRequest request = new DepositSafetyRecalculateRequest(-1L, null);

        mockMvc.perform(post("/properties/10/deposit-safety/recalculate")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
