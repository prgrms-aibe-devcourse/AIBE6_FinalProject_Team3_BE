package com.algogyeyak.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.service.SessionLogoutService;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.testsupport.CsrfHeaderMockMvcCustomizer;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AdminUserControllerTest와 동일한 패턴(@SpringBootTest + 실제 JwtProvider 발급 쿠키) - DELETE
 * /users/me가 UserService.withdraw() 커밋 후 SessionLogoutService.logout()을 실제로 호출하는지,
 * 그리고 Redis 장애로 그 호출이 실패해도 탈퇴 자체는 200으로 응답하는지를 검증한다. 세션 무효화
 * 자체의 세부 동작(쿠키 삭제, jti 블랙리스트 등록 등)은 SessionLogoutServiceTest가 이미 단위
 * 테스트로 고정해뒀으므로, 여기서는 SessionLogoutService를 mock으로 대체해 컨트롤러의 오케스트레이션
 * (호출 여부·예외 처리)만 격리해서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CsrfHeaderMockMvcCustomizer.class)
class UserControllerTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    // JwtAuthenticationFilter가 매 요청 Redis 블랙리스트를 조회하므로 실제 Redis 없이 인증을
    // 통과시키려면 mock으로 대체해야 한다(AdminUserControllerTest와 동일한 이유).
    @MockitoBean
    private AccessTokenRevocationService accessTokenRevocationService;

    @MockitoBean
    private SessionLogoutService sessionLogoutService;

    private Cookie userCookie() {
        String token = jwtProvider.createAccessToken(USER_ID, "user@example.com", Role.USER);
        return new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token);
    }

    private User activeUser() {
        User user = User.createLocalUser("user@example.com", "encoded-hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    @Test
    void 탈퇴에_성공하면_세션_무효화도_함께_호출한다() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));

        mockMvc.perform(delete("/users/me").cookie(userCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(sessionLogoutService).logout(any(), any());
    }

    @Test
    void 세션_무효화가_Redis_장애로_실패해도_탈퇴_자체는_200으로_응답한다() throws Exception {
        // 회귀 테스트 - UserService.withdraw()가 이미 커밋된 뒤라, 그 다음 단계인 세션 무효화가
        // Redis 장애(BusinessException)로 실패해도 탈퇴 자체를 실패로 되돌리면 안 된다
        // (UserController.withdraw()의 try-catch 참고).
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
        doThrow(new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE))
                .when(sessionLogoutService).logout(any(), any());

        mockMvc.perform(delete("/users/me").cookie(userCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void 인증_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(delete("/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
