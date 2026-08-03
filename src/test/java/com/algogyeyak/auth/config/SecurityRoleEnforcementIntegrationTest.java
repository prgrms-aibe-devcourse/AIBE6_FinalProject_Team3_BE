package com.algogyeyak.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * /admin/** hasRole("ADMIN") 검사가 인증은 됐지만 권한이 부족한 요청을 실제로 403으로 막는지
 * 확인한다. MockMvc(TestDispatcherServlet)로는 이 시나리오가 재현되지 않았다 - accessDeniedHandler를
 * 명시적으로 등록하기 전에는, 실제로 배포된 임베디드 Tomcat에서만 Boot의 기본 /error 포워드를 거치며
 * 401(UNAUTHORIZED)로 잘못 응답되는 회귀가 있었다(MockMvc에서는 항상 정상적으로 403이 나왔음).
 * 그래서 이 테스트만 예외적으로 실제 임베디드 서버 + 진짜 HTTP 클라이언트(RANDOM_PORT +
 * TestRestTemplate)로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SecurityRoleEnforcementIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 일반유저_토큰으로_실제_서버에_요청하면_403이다() {
        User user = User.createLocalUser("real-user@example.com", "hash", "real-user");
        user = userRepository.saveAndFlush(user);
        String token = jwtProvider.createAccessToken(user.getId(), user.getEmail(), Role.USER);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "access_token=" + token);
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/users", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    void 관리자_토큰으로_실제_서버에_요청하면_200이다() {
        User admin = User.createLocalUser("real-admin@example.com", "hash", "real-admin");
        admin.grantAdminRole();
        admin = userRepository.saveAndFlush(admin);
        String token = jwtProvider.createAccessToken(admin.getId(), admin.getEmail(), Role.ADMIN);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "access_token=" + token);
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/users", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
