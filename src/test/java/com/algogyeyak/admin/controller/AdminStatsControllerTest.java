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
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @Test
    void 일반유저_토큰으로_접근하면_403이다() throws Exception {
        mockMvc.perform(get("/admin/stats/dashboard").cookie(userCookie()))
                .andExpect(status().isForbidden());
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
