package com.algogyeyak.property.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyReport;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.testsupport.CsrfHeaderMockMvcCustomizer;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AdminUserControllerTest와 동일한 패턴 - 전체 컨텍스트 + 실제 JwtProvider로 발급한 access_token
 * 쿠키를 사용해 SecurityConfig의 /admin/** hasRole(ADMIN) 매처까지 실제로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CsrfHeaderMockMvcCustomizer.class)
class AdminPropertyReportControllerTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final Long REPORT_ID = 501L;
    private static final Long PROPERTY_ID = 101L;
    private static final Long REPORTER_ID = 3L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private PropertyReportRepository propertyReportRepository;

    @MockitoBean
    private PropertyRepository propertyRepository;

    @MockitoBean
    private UserRepository userRepository;

    // 이 테스트는 신고 처리 로직만 검증하고 blacklist 자체는 다루지 않으므로, 실제 Redis 대신
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

    private PropertyReport buildReport() {
        PropertyReport report = PropertyReport.builder()
                .propertyId(PROPERTY_ID)
                .reporterId(REPORTER_ID)
                .reason(PropertyReportReason.PRICE_MISMATCH)
                .detail(null)
                .build();
        ReflectionTestUtils.setField(report, "id", REPORT_ID);
        return report;
    }

    private Property buildProperty() {
        Property property = Property.builder()
                .userId(REPORTER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description(null)
                .build();
        ReflectionTestUtils.setField(property, "id", PROPERTY_ID);
        return property;
    }

    private User buildReporter() {
        User user = User.createLocalUser("reporter@example.com", "hash", "신고자");
        ReflectionTestUtils.setField(user, "id", REPORTER_ID);
        return user;
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
        mockMvc.perform(get("/admin/property-reports").cookie(userCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 관리자_토큰으로_목록조회에_성공한다() throws Exception {
        PropertyReport report = buildReport();
        when(propertyReportRepository.search(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(report), PageRequest.of(0, 20), 1));
        when(propertyRepository.findAllById(any())).thenReturn(List.of(buildProperty()));
        when(userRepository.findAllById(any())).thenReturn(List.of(buildReporter()));

        mockMvc.perform(get("/admin/property-reports").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reporterNickname").value("신고자"))
                .andExpect(jsonPath("$.data.content[0].status").value("RECEIVED"));
    }

    @Test
    void 신고를_조치완료로_처리한다() throws Exception {
        PropertyReport report = buildReport();
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(buildProperty()));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(buildReporter()));

        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", REPORT_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","memo":"확인 결과 문제 없음"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.reviewMemo").value("확인 결과 문제 없음"));
    }

    @Test
    void 이미_처리된_신고를_다시_처리하면_409이다() throws Exception {
        PropertyReport report = buildReport();
        report.resolve(ADMIN_ID, "먼저 처리됨");
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", REPORT_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"REJECTED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_INVALID_STATUS_TRANSITION"));
    }

    @Test
    void 존재하지_않는_신고_상세조회는_404이다() throws Exception {
        when(propertyReportRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/property-reports/{reportId}", 999L).cookie(adminCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_PROPERTY_REPORT_NOT_FOUND"));
    }
}
