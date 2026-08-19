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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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

    private static final String PROMETHEUS_PATH = "/actuator/prometheus";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String scrapeToken;

    public MetricsScrapeTokenFilter(String scrapeToken) {
        this.scrapeToken = scrapeToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!PROMETHEUS_PATH.equals(request.getRequestURI()) || hasValidToken(request)) {
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
        return scrapeToken.equals(token);
    }

    private void writeErrorResponse(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.METRICS_SCRAPE_TOKEN_INVALID.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError error = ApiError.of(ErrorCode.METRICS_SCRAPE_TOKEN_INVALID.getCode(), ErrorCode.METRICS_SCRAPE_TOKEN_INVALID.getMessage());
        response.getOutputStream().write(objectMapper.writeValueAsBytes(ApiResponse.failure(error)));
    }
}
