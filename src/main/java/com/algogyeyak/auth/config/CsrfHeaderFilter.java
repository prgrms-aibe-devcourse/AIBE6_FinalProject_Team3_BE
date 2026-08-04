package com.algogyeyak.auth.config;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.response.ApiError;
import com.algogyeyak.global.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 최소 CSRF 방어. 이 API는 {@code app.cookie.same-site=None}(크로스오리진 배포)에서 인증
 * 쿠키가 진짜 크로스사이트 요청에도 자동으로 실린다 - SameSite=Lax였다면 브라우저가 막아주던
 * 것을 이제는 직접 막아야 한다. 상태 변경 요청(POST/PUT/PATCH/DELETE)에 이 커스텀 헤더를
 * 요구하면, 순수 HTML {@code <form>} 기반 CSRF(폼은 임의 헤더를 못 붙인다)는 막히고,
 * fetch/XHR로 시도하는 경우도 브라우저가 강제하는 CORS preflight를 이미 허용된 origin
 * ({@code app.cors.allowed-origins})만 통과하므로 함께 막힌다.
 *
 * <p>바디가 필요한 POST(로그인/회원가입/매물 등록 등)는 이미 {@code application/json}
 * Content-Type이 필수라 순수 폼으로는 애초에 불가능했다 - 이 필터가 실제로 막아주는 대상은
 * 바디 없이 method+쿠키만으로 부작용을 일으키는 엔드포인트(예: {@code POST /auth/logout},
 * {@code POST /properties/{id}/checklists})다.
 *
 * <p>{@code /h2-console/**}는 로컬 전용 도구(운영에서는 비활성화됨)이고 그 UI가 이 헤더를
 * 붙이지 않으므로 제외한다.
 */
public class CsrfHeaderFilter extends OncePerRequestFilter {

    private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String REQUIRED_HEADER = "X-Requested-With";
    private static final String H2_CONSOLE_PATH_PREFIX = "/h2-console/";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean requiresHeader = STATE_CHANGING_METHODS.contains(request.getMethod())
                && !request.getRequestURI().startsWith(H2_CONSOLE_PATH_PREFIX);

        if (requiresHeader && !StringUtils.hasText(request.getHeader(REQUIRED_HEADER))) {
            writeErrorResponse(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeErrorResponse(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.CSRF_HEADER_MISSING.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError error = ApiError.of(ErrorCode.CSRF_HEADER_MISSING.getCode(), ErrorCode.CSRF_HEADER_MISSING.getMessage());
        response.getOutputStream().write(objectMapper.writeValueAsBytes(ApiResponse.failure(error)));
    }
}
