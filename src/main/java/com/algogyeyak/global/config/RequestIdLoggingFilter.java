package com.algogyeyak.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 지금까지는 백엔드 로그 어디에도 요청 단위 상관관계 ID가 없어서, 한 요청의 로그 라인들(컨트롤러 →
 * 서비스 → 리포지토리)을 운영 환경에서 확실히 묶어 볼 방법이 없었다 - 같은 순간에 여러 요청이
 * 겹치면 타임스탬프만으로는 어느 로그가 어느 요청 것인지 추측할 수밖에 없었다. 요청마다 ID를 MDC에
 * 심어(logging.pattern.level 참고) 그 요청이 끝날 때까지 남긴 모든 로그 라인에 자동으로 붙게 한다.
 *
 * <p>클라이언트(또는 앞단 로드밸런서/리버스프록시)가 이미 X-Request-Id를 붙여 보냈다면 그 값을
 * 그대로 이어받아 - 여러 홉을 거치는 요청도 같은 ID로 추적할 수 있게 하고, 없으면 새로 발급한다.
 * 응답에도 같은 헤더로 돌려줘 클라이언트/프론트 로그와도 대조할 수 있게 한다.
 *
 * <p>Spring Security 필터체인보다 앞서 항상 실행돼야 하므로(그래야 인증 관련 로그에도 ID가 붙는다)
 * SecurityConfig의 addFilterBefore가 아니라 별도 서블릿 필터로 등록한다({@link RequestIdFilterConfig}
 * 참고) - 스레드가 스레드풀에서 재사용되므로 요청이 끝나면 반드시 MDC에서 지운다.
 */
public class RequestIdLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        return StringUtils.hasText(incoming) ? incoming : UUID.randomUUID().toString();
    }
}
