package com.algogyeyak.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminStatsControllerTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long USER_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PropertyRepository propertyRepository;

    @MockitoBean
    private PropertyReportRepository propertyReportRepository;

    // 이 테스트는 통계 집계 로직만 검증하고 blacklist 자체는 다루지 않으므로, 실제 Redis 대신
    // mock으로 대체한다(mock 기본값 false = "블랙리스트에 없음"이라 정상 인증 흐름을 그대로 탄다).
    @MockitoBean
    private AccessTokenRevocationService accessTokenRevocationService;

    private Cookie adminCookie() {
        String token = jwtProvider.createAccessToken(ADMIN_ID, "admin@example.com", Role.ADMIN);
        return new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token);
    }

    private Cookie userCookie() {
        String token = jwtProvider.createAccessToken(USER_ID, "user@example.com", Role.USER);
        return new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token);
    }

    // JwtAuthenticationFilter가 매 요청 DB에서 유저 상태/권한을 재확인하므로, 토큰 주체(ADMIN_ID/
    // USER_ID)에 대한 활성 유저 스텁이 없으면 인증 자체가 401로 실패한다 - AdminUserControllerTest와 동일.
    @BeforeEach
    void stubAuthenticatedUsersForFilter() {
        User admin = User.createLocalUser("admin@example.com", "hash", "관리자");
        ReflectionTestUtils.setField(admin, "id", ADMIN_ID);
        ReflectionTestUtils.setField(admin, "role", Role.ADMIN);
        User user = User.createLocalUser("user@example.com", "hash", "일반유저");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void 일반유저_토큰으로_접근하면_403이다() throws Exception {
        mockMvc.perform(get("/admin/stats/dashboard").cookie(userCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 인증토큰_없이_접근하면_401이다() throws Exception {
        mockMvc.perform(get("/admin/stats/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_토큰으로_대시보드_통계를_조회한다() throws Exception {
        List<Long> userIdsJoinedInRange = LongStream.rangeClosed(1, 10).boxed().toList();

        when(userRepository.countByCreatedAtBetween(any(), any())).thenReturn(10L);
        when(userRepository.findIdsByCreatedAtBetween(any(), any())).thenReturn(userIdsJoinedInRange);
        when(propertyRepository.countByCreatedAtBetween(any(), any())).thenReturn(5L);
        when(propertyReportRepository.countByStatusAndCreatedAtBetween(eq(PropertyReportStatus.RECEIVED), any(), any()))
                .thenReturn(2L);
        when(userRepository.findCreatedAtBetween(any(), any())).thenReturn(List.of(LocalDateTime.now()));
        when(propertyRepository.findCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(propertyRepository.countDistinctUserIdIn(userIdsJoinedInRange)).thenReturn(4L);
        when(propertyReportRepository.countGroupedByReasonAndCreatedAtBetween(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/stats/dashboard").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.newUsers").value(10))
                .andExpect(jsonPath("$.data.summary.newProperties").value(5))
                .andExpect(jsonPath("$.data.summary.newPendingReports").value(2))
                .andExpect(jsonPath("$.data.trends.signups.length()").value(14))
                .andExpect(jsonPath("$.data.distributions.byPropertyRegistration.length()").value(2))
                .andExpect(jsonPath("$.data.distributions.byPropertyRegistration[0].registered").value(true))
                .andExpect(jsonPath("$.data.distributions.byPropertyRegistration[0].count").value(4))
                .andExpect(jsonPath("$.data.distributions.byPropertyRegistration[1].registered").value(false))
                .andExpect(jsonPath("$.data.distributions.byPropertyRegistration[1].count").value(6));
    }

    // 회귀 테스트 - 신고 사유별 분포가 사유별 COUNT 쿼리 여러 번(enum 5개)이 아니라 GROUP BY
    // 집계 쿼리 한 번으로 바뀌었다 - 실제 접수된 사유는 그 건수가, 접수되지 않은 사유는 0건이
    // 그대로 응답에 나타나야 한다(모든 사유가 항상 목록에 나타난다는 기존 계약 유지).
    @Test
    void 신고_사유별_분포는_GROUP_BY_결과를_사유별_건수로_매핑하고_나머지는_0건으로_채운다() throws Exception {
        when(userRepository.countByCreatedAtBetween(any(), any())).thenReturn(0L);
        when(userRepository.findIdsByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(propertyRepository.countByCreatedAtBetween(any(), any())).thenReturn(0L);
        when(propertyReportRepository.countByStatusAndCreatedAtBetween(eq(PropertyReportStatus.RECEIVED), any(), any()))
                .thenReturn(0L);
        when(userRepository.findCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(propertyRepository.findCreatedAtBetween(any(), any())).thenReturn(List.of());

        PropertyReportRepository.ReasonCount duplicateCount = mock(PropertyReportRepository.ReasonCount.class);
        when(duplicateCount.getReason()).thenReturn(PropertyReportReason.DUPLICATE);
        when(duplicateCount.getCount()).thenReturn(3L);
        when(propertyReportRepository.countGroupedByReasonAndCreatedAtBetween(any(), any()))
                .thenReturn(List.of(duplicateCount));

        mockMvc.perform(get("/admin/stats/dashboard").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.distributions.byReportReason.length()").value(5))
                .andExpect(jsonPath("$.data.distributions.byReportReason[?(@.reason=='DUPLICATE')].count").value(3))
                .andExpect(jsonPath("$.data.distributions.byReportReason[?(@.reason=='ETC')].count").value(0));
    }

    @Test
    void 기간_내_가입자가_없으면_등록자_조회를_건너뛰고_0으로_집계한다() throws Exception {
        when(userRepository.countByCreatedAtBetween(any(), any())).thenReturn(10L);
        when(userRepository.findIdsByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(propertyRepository.countByCreatedAtBetween(any(), any())).thenReturn(5L);
        when(propertyReportRepository.countByStatusAndCreatedAtBetween(eq(PropertyReportStatus.RECEIVED), any(), any()))
                .thenReturn(2L);
        when(userRepository.findCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(propertyRepository.findCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(propertyReportRepository.countGroupedByReasonAndCreatedAtBetween(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/stats/dashboard").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.distributions.byPropertyRegistration[0].count").value(0))
                .andExpect(jsonPath("$.data.distributions.byPropertyRegistration[1].count").value(0));

        verify(propertyRepository, never()).countDistinctUserIdIn(any());
    }

    @Test
    void 조회_기간을_직접_지정하면_해당_일수만큼_추이를_반환한다() throws Exception {
        when(userRepository.countByCreatedAtBetween(any(), any())).thenReturn(10L);
        when(userRepository.findIdsByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(propertyRepository.countByCreatedAtBetween(any(), any())).thenReturn(5L);
        when(propertyReportRepository.countByStatusAndCreatedAtBetween(eq(PropertyReportStatus.RECEIVED), any(), any()))
                .thenReturn(2L);
        when(userRepository.findCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(propertyRepository.findCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(propertyReportRepository.countGroupedByReasonAndCreatedAtBetween(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/stats/dashboard")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-05")
                        .cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trends.signups.length()").value(5))
                .andExpect(jsonPath("$.data.trends.signups[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.data.trends.signups[4].date").value("2026-01-05"));
    }

    @Test
    void 시작일이_종료일보다_늦으면_400이다() throws Exception {
        mockMvc.perform(get("/admin/stats/dashboard")
                        .param("startDate", "2026-01-10")
                        .param("endDate", "2026-01-01")
                        .cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMIN_INVALID_DATE_RANGE"));
    }

    @Test
    void 조회_기간이_90일을_초과하면_400이다() throws Exception {
        mockMvc.perform(get("/admin/stats/dashboard")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-04-15")
                        .cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMIN_INVALID_DATE_RANGE"));
    }
}
