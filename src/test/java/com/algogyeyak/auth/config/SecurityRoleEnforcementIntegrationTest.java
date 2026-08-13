package com.algogyeyak.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * /admin/** hasRole("ADMIN") 검사가 인증은 됐지만 권한이 부족한 요청을 실제로 403으로 막는지
 * 확인한다. MockMvc(TestDispatcherServlet)로는 이 시나리오가 재현되지 않았다 - accessDeniedHandler를
 * 명시적으로 등록하기 전에는, 실제로 배포된 임베디드 Tomcat에서만 Boot의 기본 /error 포워드를 거치며
 * 401(UNAUTHORIZED)로 잘못 응답되는 회귀가 있었다(MockMvc에서는 항상 정상적으로 403이 나왔음).
 * 그래서 이 테스트만 예외적으로 실제 임베디드 서버 + 진짜 HTTP 클라이언트(RANDOM_PORT +
 * TestRestTemplate)로 검증한다.
 */
// 실제 임베디드 서버로 필터 체인 전체를 태우므로 AccessTokenRevocationService(→Redis)도 mock 없이
// 진짜로 동작해야 한다 - AuthControllerTest의 accessTokenIsRejectedAfterLogout*()과 동일한 이유.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class SecurityRoleEnforcementIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    // 이 클래스는 실제 임베디드 서버로 요청을 보내므로(클래스 상단 설명) @Transactional 롤백을 쓸 수
    // 없다 - 요청을 처리하는 서버 스레드가 테스트 메서드의 트랜잭션을 보지 못하고 커밋된 데이터만
    // 봐야 하기 때문이다. 그래서 각 테스트가 만든 유저 id를 직접 기록해두고 @AfterEach에서 지운다 -
    // 안 그러면 이 ADMIN+ACTIVE 유저가 같은 H2 인스턴스를 공유하는 다른 통합 테스트의 "어떤 admin이
    // 몇 명 존재하는지" 전제를 깨뜨릴 수 있다.
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUpCreatedUsers() {
        userRepository.deleteAllById(createdUserIds);
        createdUserIds.clear();
    }

    @Test
    void 일반유저_토큰으로_실제_서버에_요청하면_403이다() {
        User user = User.createLocalUser("real-user@example.com", "hash", "real-user");
        user = userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());
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
        createdUserIds.add(admin.getId());
        String token = jwtProvider.createAccessToken(admin.getId(), admin.getEmail(), Role.ADMIN);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "access_token=" + token);
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/users", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
