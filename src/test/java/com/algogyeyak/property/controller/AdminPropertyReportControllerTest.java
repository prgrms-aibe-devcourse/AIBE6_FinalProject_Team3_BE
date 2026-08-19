package com.algogyeyak.property.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import com.algogyeyak.property.entity.PropertyReportStatus;
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
import org.springframework.dao.DataAccessResourceFailureException;
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
    void 인증토큰_없이_목록조회하면_401이다() throws Exception {
        mockMvc.perform(get("/admin/property-reports"))
                .andExpect(status().isUnauthorized());
    }

    // PropertyReportRepository는 mock이라 필터가 실제로 동작하는지는 확인할 수 없다 - 쿼리
    // 파라미터가 리포지토리 호출까지 그대로 전달되는지만 확인한다.
    @Test
    void 목록조회_필터가_리포지토리_호출까지_그대로_전달된다() throws Exception {
        when(propertyReportRepository.search(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/property-reports")
                        .param("status", "RESOLVED")
                        .param("reason", "DUPLICATE")
                        .cookie(adminCookie()))
                .andExpect(status().isOk());

        verify(propertyReportRepository).search(
                eq(PropertyReportStatus.RESOLVED), eq(PropertyReportReason.DUPLICATE), any());
    }

    @Test
    void 허용되지_않는_정렬_필드로_목록조회하면_400이다() throws Exception {
        mockMvc.perform(get("/admin/property-reports").param("sort", "id").cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SORT_FIELD"));
    }

    @Test
    void 페이지_크기가_100을_초과하면_목록조회가_400이다() throws Exception {
        mockMvc.perform(get("/admin/property-reports").param("size", "101").cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void 신고_상세조회에_성공한다() throws Exception {
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(buildReport()));
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(buildProperty()));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(buildReporter()));

        mockMvc.perform(get("/admin/property-reports/{reportId}", REPORT_ID).cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reporterNickname").value("신고자"))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));
    }

    @Test
    void 상세조회는_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(get("/admin/property-reports/{reportId}", REPORT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 상세조회는_비관리자면_403이다() throws Exception {
        mockMvc.perform(get("/admin/property-reports/{reportId}", REPORT_ID).cookie(userCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
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
    void 본인이_등록한_신고를_직접_처리하면_409이다() throws Exception {
        // PropertyReport는 매물 소유자 본인이 등록하는 자가 신고다 - 그 소유자가 ADMIN이면
        // 자기 신고를 자기가 확정해버릴 수 있으므로(제3자 검토 절차 무력화) 반드시 막혀야 한다.
        PropertyReport ownReport = PropertyReport.builder()
                .propertyId(PROPERTY_ID)
                .reporterId(ADMIN_ID)
                .reason(PropertyReportReason.PRICE_MISMATCH)
                .detail(null)
                .build();
        ReflectionTestUtils.setField(ownReport, "id", REPORT_ID);
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(ownReport));

        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", REPORT_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_PROPERTY_REPORT_SELF_REVIEW"));
    }

    @Test
    void 신고를_반려로_처리한다() throws Exception {
        PropertyReport report = buildReport();
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(buildProperty()));
        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(buildReporter()));

        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", REPORT_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"REJECTED","memo":"근거 불충분"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewMemo").value("근거 불충분"));
    }

    @Test
    void 존재하지_않는_신고를_검토처리하면_404이다() throws Exception {
        when(propertyReportRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", 999L)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_PROPERTY_REPORT_NOT_FOUND"));
    }

    @Test
    void status에_RESOLVED_REJECTED_외_값을_넣으면_409이다() throws Exception {
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(buildReport()));

        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", REPORT_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RECEIVED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_INVALID_STATUS_TRANSITION"));
    }

    @Test
    void status_필드가_없으면_검토처리가_400이다() throws Exception {
        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", REPORT_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void memo가_500자를_넘으면_검토처리가_400이다() throws Exception {
        String tooLong = "가".repeat(501);

        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", REPORT_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","memo":"%s"}
                                """.formatted(tooLong)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 검토처리는_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 검토처리는_비관리자면_403이다() throws Exception {
        mockMvc.perform(patch("/admin/property-reports/{reportId}/review", REPORT_ID)
                        .cookie(userCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 일괄_검토처리에_성공한다() throws Exception {
        PropertyReport report = buildReport();
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

        mockMvc.perform(patch("/admin/property-reports/bulk-review")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportIds":[%d],"status":"RESOLVED","memo":"일괄 확인"}
                                """.formatted(REPORT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeededIds[0]").value(REPORT_ID))
                .andExpect(jsonPath("$.data.failures").isEmpty());
    }

    // 본인이 등록한 신고가 목록에 섞여 있어도, 그 항목만 실패 목록에 담기고 나머지는 정상 처리돼야 한다.
    @Test
    void 일괄_검토처리에서_본인_신고는_실패목록에만_담기고_나머지는_처리된다() throws Exception {
        PropertyReport othersReport = buildReport();
        PropertyReport ownReport = PropertyReport.builder()
                .propertyId(PROPERTY_ID)
                .reporterId(ADMIN_ID)
                .reason(PropertyReportReason.PRICE_MISMATCH)
                .detail(null)
                .build();
        ReflectionTestUtils.setField(ownReport, "id", 999L);
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(othersReport));
        when(propertyReportRepository.findById(999L)).thenReturn(Optional.of(ownReport));

        mockMvc.perform(patch("/admin/property-reports/bulk-review")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportIds":[%d,999],"status":"RESOLVED"}
                                """.formatted(REPORT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeededIds[0]").value(REPORT_ID))
                .andExpect(jsonPath("$.data.succeededIds.length()").value(1))
                .andExpect(jsonPath("$.data.failures[0].id").value(999));
    }

    // 회귀 테스트 - BusinessException이 아닌 예외(예: DB 접근 오류)가 배치 중간 항목에서 나면,
    // 이 예외가 잡히지 않고 트랜잭션 프록시 경계를 벗어나 전체 트랜잭션이 롤백되며 이미 처리된
    // 앞 항목까지 함께 취소되던 버그가 있었다. 지금은 이런 예외도 흡수해 500이 아니라 200으로
    // 응답하고, 앞선 성공은 succeededIds에 그대로 남아야 한다.
    @Test
    void 일괄_검토처리는_BusinessException이_아닌_예외도_흡수하고_앞선_성공을_유지한다() throws Exception {
        PropertyReport report = buildReport();
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(propertyReportRepository.findById(999L))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        mockMvc.perform(patch("/admin/property-reports/bulk-review")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportIds":[%d,999],"status":"RESOLVED"}
                                """.formatted(REPORT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeededIds[0]").value(REPORT_ID))
                .andExpect(jsonPath("$.data.succeededIds.length()").value(1))
                .andExpect(jsonPath("$.data.failures[0].id").value(999));
    }

    @Test
    void 일괄_검토처리는_reportIds가_비어있으면_400이다() throws Exception {
        mockMvc.perform(patch("/admin/property-reports/bulk-review")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportIds":[],"status":"RESOLVED"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // 회귀 테스트 - 원소 null 검증(@NotNull List 원소)이 없으면 이 요청이 그대로 서비스까지
    // 들어가 findById(null)에서 IllegalArgumentException으로 500이 됐다.
    @Test
    void 일괄_검토처리는_reportIds에_null이_섞이면_400이다() throws Exception {
        mockMvc.perform(patch("/admin/property-reports/bulk-review")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportIds":[null],"status":"RESOLVED"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // 회귀 테스트 - 중복 제거 전에는 첫 시도에서 RESOLVED로 확정된 신고가 두 번째 시도에서
    // "이미 검토 완료"(ADMIN_INVALID_STATUS_TRANSITION)로 걸려 같은 id가 성공/실패 양쪽에
    // 나타날 수 있었다.
    @Test
    void 일괄_검토처리에서_중복된_id는_한_번만_처리된다() throws Exception {
        PropertyReport report = buildReport();
        when(propertyReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

        mockMvc.perform(patch("/admin/property-reports/bulk-review")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportIds":[%d,%d],"status":"RESOLVED"}
                                """.formatted(REPORT_ID, REPORT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeededIds.length()").value(1))
                .andExpect(jsonPath("$.data.failures").isEmpty());
    }

    @Test
    void 일괄_검토처리는_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(patch("/admin/property-reports/bulk-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportIds":[%d],"status":"RESOLVED"}
                                """.formatted(REPORT_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 일괄_검토처리는_비관리자면_403이다() throws Exception {
        mockMvc.perform(patch("/admin/property-reports/bulk-review")
                        .cookie(userCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportIds":[%d],"status":"RESOLVED"}
                                """.formatted(REPORT_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 존재하지_않는_신고_상세조회는_404이다() throws Exception {
        when(propertyReportRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/property-reports/{reportId}", 999L).cookie(adminCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_PROPERTY_REPORT_NOT_FOUND"));
    }
}
