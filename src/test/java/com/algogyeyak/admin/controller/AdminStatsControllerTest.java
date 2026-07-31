package com.algogyeyak.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
    void 관리자_토큰으로_대시보드_통계를_조회한다() throws Exception {
        when(userRepository.count()).thenReturn(10L);
        when(propertyRepository.countByStatus(PropertyStatus.ACTIVE)).thenReturn(5L);
        when(propertyReportRepository.countByStatus(PropertyReportStatus.RECEIVED)).thenReturn(2L);
        when(userRepository.findCreatedAtSince(any())).thenReturn(List.of(LocalDateTime.now()));
        when(propertyRepository.findCreatedAtSince(any())).thenReturn(List.of());
        when(userRepository.countByRole(Role.USER)).thenReturn(9L);
        when(userRepository.countByRole(Role.ADMIN)).thenReturn(1L);
        when(propertyReportRepository.countByReason(any())).thenReturn(0L);

        mockMvc.perform(get("/admin/stats/dashboard").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalUsers").value(10))
                .andExpect(jsonPath("$.data.summary.totalProperties").value(5))
                .andExpect(jsonPath("$.data.summary.pendingReports").value(2))
                .andExpect(jsonPath("$.data.trends.signups.length()").value(14))
                .andExpect(jsonPath("$.data.distributions.byRole.length()").value(2));
    }
}
