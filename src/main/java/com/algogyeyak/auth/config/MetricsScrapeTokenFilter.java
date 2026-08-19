package com.algogyeyak.auth.config;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.response.ApiError;
import com.algogyeyak.global.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code /actuator/prometheus}는 SecurityConfig에서 permitAll이다 - 리버스 프록시
 * (nginx-proxy-manager, 이 저장소 밖에서 설정됨)가 이 경로를 외부에 막아준다는 전제였는데,
 * 그 설정은 여기서 검증할 방법이 없다. 프록시 설정 실수만으로 내부 지표(JVM/DB 커넥션 풀 등)가
 * 통째로 공개되는 걸 막기 위해, 리버스 프록시와 무관하게 애플리케이션 레벨에서 한 번 더 막는다
 * (defense in depth) - {@code /actuator/health}는 대상이 아니다(헬스체크는 로드밸런서/업타임
 * 모니터링이 인증 없이 호출해야 하고, 노출되는 정보도 "떠 있다/아니다" 정도라 위험이 낮다).
 *
 * <p>Prometheus 자체의 {@code bearer_token}/{@code bearer_token_file} 스크래핑 옵션과 맞춰
 * {@code Authorization: Bearer <token>} 헤더를 검사한다 - monitoring/prometheus.yml 참고.
 */
public class MetricsScrapeTokenFilter extends OncePerRequestFilter {

    // request.getRequestURI()를 String.equals로 직접 비교하면 안 된다 - 그건 서블릿 컨테이너가
    // 돌려주는 원본(디코딩 전) 경로라 /actuator/prometheus;x=1 같은 matrix parameter가 붙으면 그대로
    // 안 맞아 이 필터 자체가 "보호 대상 경로가 아니다"로 오판해 통과시켜버린다(SecurityConfig의
    // authorizeHttpRequests도 이 경로를 permitAll로 열어두고 이 필터 하나만 믿고 있어서, 그 순간
    // 인증이 통째로 사라진다). PathPatternRequestMatcher는 Spring Security 전체가 이미 같은 목적으로
    // 쓰는 정규화된 매처(SecurityConfig의 /h2-console/** 매칭과 동일)라 이 필터의 "보호 대상"
    // 판단이 SecurityConfig의 실제 매칭과 항상 같은 방식으로 어긋나지 않는다.
    private static final RequestMatcher PROMETHEUS_MATCHER = PathPatternRequestMatcher.pathPattern("/actuator/prometheus");
    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String scrapeToken;

    public MetricsScrapeTokenFilter(String scrapeToken) {
        this.scrapeToken = scrapeToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!PROMETHEUS_MATCHER.matches(request) || hasValidToken(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        writeErrorResponse(response);
    }

    private boolean hasValidToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String token = header.substring(BEARER_PREFIX.length());
        // 타이밍 공격 방어(defense-in-depth) - 이 엔드포인트는 외부에 포트를 안 열어(docker-compose.monitoring.yml
        // 참고) 원격 타이밍 공격 실익은 낮지만, 비교 비용이 String.equals 대비 다르지 않으니 그냥 상수시간으로 둔다.
        return MessageDigest.isEqual(
                scrapeToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private void writeErrorResponse(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.METRICS_SCRAPE_TOKEN_INVALID.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError error = ApiError.of(ErrorCode.METRICS_SCRAPE_TOKEN_INVALID.getCode(), ErrorCode.METRICS_SCRAPE_TOKEN_INVALID.getMessage());
        response.getOutputStream().write(objectMapper.writeValueAsBytes(ApiResponse.failure(error)));
    }
}
