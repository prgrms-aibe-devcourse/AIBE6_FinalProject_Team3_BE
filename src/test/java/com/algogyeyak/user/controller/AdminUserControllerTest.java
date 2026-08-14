package com.algogyeyak.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.algogyeyak.testsupport.CsrfHeaderMockMvcCustomizer;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
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
 * AuthControllerTest와 동일한 패턴(@SpringBootTest + 실제 JwtProvider로 발급한 access_token 쿠키) -
 * SecurityConfig의 requestMatchers("/admin/**").hasRole("ADMIN")이 실제로 걸려있는지까지 검증하려면
 * 이 슬라이스가 아니라 전체 컨텍스트가 필요하다(@WebMvcTest는 SecurityConfig를 로드하지 않음 -
 * PropertyControllerTest 주석 참고).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CsrfHeaderMockMvcCustomizer.class)
class AdminUserControllerTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final Long TARGET_ID = 3L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    // 이 테스트는 유저 관리 로직만 검증하고 blacklist 자체는 다루지 않으므로, 실제 Redis 대신
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

    private User buildUser(Long id, String email, String nickname, Role role) {
        User user = User.createLocalUser(email, "hash", nickname);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "role", role);
        return user;
    }

    // JwtAuthenticationFilter가 매 요청 DB에서 유저 상태/권한을 재확인하므로, 토큰의 주체(ADMIN_ID/
    // USER_ID)에 대한 활성 유저 스텁이 없으면 인증 자체가 실패해 각 테스트의 실제 목적(200/403/404 등)을
    // 검증하기 전에 401로 끝나버린다. 개별 테스트가 다른 대상(TARGET_ID)을 별도로 스텁하는 것과는
    // 별개로, 필터 통과를 위한 최소 조건만 여기서 채워둔다.
    @BeforeEach
    void stubAuthenticatedUsersForFilter() {
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(buildUser(ADMIN_ID, "admin@example.com", "관리자", Role.ADMIN)));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser(USER_ID, "user@example.com", "일반유저", Role.USER)));
    }

    @Test
    void 일반유저_토큰으로_접근하면_403이다() throws Exception {
        // accessDeniedHandler를 명시적으로 등록하기 전에는 Boot의 기본 /error 포워드를 거치며
        // 실제 배포 환경(Tomcat)에서 401로 잘못 응답되는 회귀가 있었다(MockMvc에서는 재현되지
        // 않았음) - 상태코드뿐 아니라 우리 공통 응답 포맷/코드까지 확인해 그 회귀를 다시 잡는다.
        mockMvc.perform(get("/admin/users").cookie(userCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 인증토큰_없이_접근하면_401이다() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_토큰으로_목록조회에_성공한다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(target), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/users").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].nickname").value("타겟유저"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // UserRepository는 mock이라 LIKE/정확일치가 실제로 동작하는지는 확인할 수 없다(그건
    // UserRepositoryTest가 실제 H2로 검증) - 여기서는 쿼리 파라미터가 서비스/리포지토리
    // 호출까지 그대로 전달되는지만 확인한다.
    @Test
    void 목록조회_검색조건이_리포지토리_호출까지_그대로_전달된다() throws Exception {
        when(userRepository.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/users")
                        .param("email", "target@example.com")
                        .param("nickname", "타겟")
                        .param("role", "ADMIN")
                        .param("status", "SUSPENDED")
                        .cookie(adminCookie()))
                .andExpect(status().isOk());

        verify(userRepository).search(
                eq("target@example.com"), eq("타겟"), eq(Role.ADMIN), eq(UserStatus.SUSPENDED), any());
    }

    @Test
    void 허용되지_않는_정렬_필드로_목록조회하면_400이다() throws Exception {
        mockMvc.perform(get("/admin/users").param("sort", "id").cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SORT_FIELD"));
    }

    @Test
    void 페이지_크기가_100을_초과하면_목록조회가_400이다() throws Exception {
        mockMvc.perform(get("/admin/users").param("size", "101").cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void 관리자_토큰으로_권한변경에_성공한다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.updateRoleIfNotWithdrawn(TARGET_ID, Role.ADMIN, UserStatus.WITHDRAWN)).thenReturn(1);

        mockMvc.perform(patch("/admin/users/{userId}/role", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void 자기자신의_권한은_변경할_수_없다() throws Exception {
        mockMvc.perform(patch("/admin/users/{userId}/role", ADMIN_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"USER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void 탈퇴한_유저의_권한은_변경할_수_없다() throws Exception {
        // 형제 API인 상태변경(정지/활성화)은 탈퇴 유저를 이미 거부하고 있었는데(User.suspend/activate),
        // 권한변경(User.changeRole)만 이 가드가 빠져 있어 익명화된 탈퇴 계정도 조용히 ADMIN으로
        // 승격/강등될 수 있던 불일치의 회귀 테스트.
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        target.withdraw();
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        mockMvc.perform(patch("/admin/users/{userId}/role", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_INVALID_ROLE_TRANSITION"));
    }

    @Test
    void 마지막_남은_활성_관리자는_강등할_수_없다() throws Exception {
        // 관리자 두 명이 서로 실수로/의도적으로 유일하게 남은 관리자를 강등시키면, 그 순간부터
        // 아무도 /admin/**에 접근할 수 없게 되고 AdminAccountSeeder는 기존 계정을 절대 다시
        // 승격시키지 않으므로 앱 안에서 되돌릴 방법이 없다 - 그 경로를 막는 회귀 테스트.
        User target = buildUser(TARGET_ID, "target@example.com", "관리자타겟", Role.ADMIN);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(target));

        mockMvc.perform(patch("/admin/users/{userId}/role", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"USER"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_LAST_ADMIN_ACCOUNT"));
    }

    @Test
    void 다른_활성_관리자가_남아있으면_강등할_수_있다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "관리자타겟", Role.ADMIN);
        User otherAdmin = buildUser(99L, "other-admin@example.com", "다른관리자", Role.ADMIN);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(target, otherAdmin));
        when(userRepository.updateRoleIfNotWithdrawn(TARGET_ID, Role.USER, UserStatus.WITHDRAWN)).thenReturn(1);

        mockMvc.perform(patch("/admin/users/{userId}/role", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"USER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void 마지막_남은_활성_관리자는_정지할_수_없다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "관리자타겟", Role.ADMIN);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(target));

        mockMvc.perform(patch("/admin/users/{userId}/status", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_LAST_ADMIN_ACCOUNT"));
    }

    @Test
    void 정지된_유저는_로그인_경로에서_거부되어야_하므로_상태변경_API가_SUSPENDED를_반영한다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.updateStatusIfNotWithdrawn(TARGET_ID, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(1);

        mockMvc.perform(patch("/admin/users/{userId}/status", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    void 정지된_유저를_다시_활성화할_수_있다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        target.suspend();
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.updateStatusIfNotWithdrawn(TARGET_ID, UserStatus.ACTIVE, UserStatus.WITHDRAWN)).thenReturn(1);

        mockMvc.perform(patch("/admin/users/{userId}/status", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void 자기자신의_상태는_변경할_수_없다() throws Exception {
        mockMvc.perform(patch("/admin/users/{userId}/status", ADMIN_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void 탈퇴한_유저의_상태는_변경할_수_없다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        target.withdraw();
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        mockMvc.perform(patch("/admin/users/{userId}/status", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_INVALID_STATUS_TRANSITION"));
    }

    @Test
    void status에_ACTIVE_SUSPENDED_외_값을_넣으면_409이다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        mockMvc.perform(patch("/admin/users/{userId}/status", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"WITHDRAWN"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_INVALID_STATUS_TRANSITION"));
    }

    @Test
    void 존재하지_않는_유저의_상태변경은_404이다() throws Exception {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/admin/users/{userId}/status", 999L)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_USER_NOT_FOUND"));
    }

    @Test
    void 상태변경은_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(patch("/admin/users/{userId}/status", TARGET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 상태변경은_비관리자면_403이다() throws Exception {
        mockMvc.perform(patch("/admin/users/{userId}/status", TARGET_ID)
                        .cookie(userCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 존재하지_않는_유저의_권한변경은_404이다() throws Exception {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/admin/users/{userId}/role", 999L)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_USER_NOT_FOUND"));
    }

    @Test
    void role_필드가_없으면_권한변경이_400이다() throws Exception {
        mockMvc.perform(patch("/admin/users/{userId}/role", TARGET_ID)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 권한변경은_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(patch("/admin/users/{userId}/role", TARGET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 권한변경은_비관리자면_403이다() throws Exception {
        mockMvc.perform(patch("/admin/users/{userId}/role", TARGET_ID)
                        .cookie(userCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 일괄_상태변경에_성공한다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.updateStatusIfNotWithdrawn(TARGET_ID, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(1);

        mockMvc.perform(patch("/admin/users/bulk-status")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":[%d],"status":"SUSPENDED"}
                                """.formatted(TARGET_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeededIds[0]").value(TARGET_ID))
                .andExpect(jsonPath("$.data.failures").isEmpty());
    }

    // 목록에 자기 자신의 id가 섞여 있어도, 그 항목만 실패 목록에 담기고 나머지는 정상 처리돼야 한다.
    @Test
    void 일괄_상태변경에서_자기자신_id는_실패목록에만_담기고_나머지는_처리된다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.updateStatusIfNotWithdrawn(TARGET_ID, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(1);

        mockMvc.perform(patch("/admin/users/bulk-status")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":[%d,%d],"status":"SUSPENDED"}
                                """.formatted(TARGET_ID, ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeededIds[0]").value(TARGET_ID))
                .andExpect(jsonPath("$.data.succeededIds.length()").value(1))
                .andExpect(jsonPath("$.data.failures[0].id").value(ADMIN_ID))
                .andExpect(jsonPath("$.data.failures[0].errorCode").value("BAD_REQUEST"));
    }

    @Test
    void 일괄_상태변경은_userIds가_비어있으면_400이다() throws Exception {
        mockMvc.perform(patch("/admin/users/bulk-status")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":[],"status":"SUSPENDED"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // 회귀 테스트 - 원소 null 검증(@NotNull List 원소)이 없으면 이 요청이 그대로 서비스까지
    // 들어가 findById(null)에서 IllegalArgumentException으로 500이 됐다.
    @Test
    void 일괄_상태변경은_userIds에_null이_섞이면_400이다() throws Exception {
        mockMvc.perform(patch("/admin/users/bulk-status")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":[null],"status":"SUSPENDED"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // 회귀 테스트 - 중복 제거 전에는 같은 id가 성공/실패 목록 양쪽에 나타날 수 있었다(정지 후
    // 다시 정지 시도 시 상태 전이 규칙에 걸릴 수 있는 경우 등).
    @Test
    void 일괄_상태변경에서_중복된_id는_한_번만_처리된다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.updateStatusIfNotWithdrawn(TARGET_ID, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(1);

        mockMvc.perform(patch("/admin/users/bulk-status")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":[%d,%d],"status":"SUSPENDED"}
                                """.formatted(TARGET_ID, TARGET_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeededIds.length()").value(1))
                .andExpect(jsonPath("$.data.failures").isEmpty());
    }

    @Test
    void 일괄_상태변경은_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(patch("/admin/users/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":[%d],"status":"SUSPENDED"}
                                """.formatted(TARGET_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 일괄_상태변경은_비관리자면_403이다() throws Exception {
        mockMvc.perform(patch("/admin/users/bulk-status")
                        .cookie(userCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":[%d],"status":"SUSPENDED"}
                                """.formatted(TARGET_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 유저_상세조회에_성공한다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        mockMvc.perform(get("/admin/users/{userId}", TARGET_ID).cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("타겟유저"));
    }

    @Test
    void 존재하지_않는_유저_상세조회는_404이다() throws Exception {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/users/{userId}", 999L).cookie(adminCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_USER_NOT_FOUND"));
    }

    @Test
    void 상세조회는_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(get("/admin/users/{userId}", TARGET_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 상세조회는_비관리자면_403이다() throws Exception {
        mockMvc.perform(get("/admin/users/{userId}", TARGET_ID).cookie(userCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
