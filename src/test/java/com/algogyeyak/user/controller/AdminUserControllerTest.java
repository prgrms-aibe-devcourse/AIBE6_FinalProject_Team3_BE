package com.algogyeyak.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Test
    void 관리자_토큰으로_권한변경에_성공한다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

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
    void 정지된_유저는_로그인_경로에서_거부되어야_하므로_상태변경_API가_SUSPENDED를_반영한다() throws Exception {
        User target = buildUser(TARGET_ID, "target@example.com", "타겟유저", Role.USER);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

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
    void 존재하지_않는_유저_상세조회는_404이다() throws Exception {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/users/{userId}", 999L).cookie(adminCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_USER_NOT_FOUND"));
    }
}
