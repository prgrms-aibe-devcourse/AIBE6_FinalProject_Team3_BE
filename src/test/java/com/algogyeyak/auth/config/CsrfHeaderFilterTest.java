package com.algogyeyak.auth.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 회귀 테스트 - requestURI.startsWith("/h2-console")는 "/h2-console-admin"처럼 실제로는 다른
 * 경로인데 우연히 같은 접두어를 공유하는 미래의 상태 변경 API까지 CSRF 검사를 우회시켰다.
 * 정확히 "/h2-console" 또는 "/h2-console/..."만 예외로 두는지 확인한다.
 */
class CsrfHeaderFilterTest {

    private final CsrfHeaderFilter filter = new CsrfHeaderFilter(List.of("https://allowed.example.com"));

    private int doFilter(String requestURI) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", requestURI);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        if (response.getStatus() == 200) {
            verify(chain).doFilter(request, response);
        } else {
            verify(chain, never()).doFilter(request, response);
        }
        return response.getStatus();
    }

    @Test
    void exemptsExactH2ConsoleBasePathWithoutCsrfSignal() throws Exception {
        assertEquals(200, doFilter("/h2-console"));
    }

    @Test
    void exemptsH2ConsoleSubPathsWithoutCsrfSignal() throws Exception {
        assertEquals(200, doFilter("/h2-console/login.do"));
    }

    @Test
    void doesNotExemptPathsThatOnlySharePrefixWithH2Console() throws Exception {
        // "/h2-console-admin"은 H2 콘솔이 아니지만 startsWith("/h2-console")로는 구분되지 않았다 -
        // CSRF 신호(X-Requested-With/Origin) 없이 통과되면 안 된다.
        assertEquals(403, doFilter("/h2-console-admin/action"));
    }
}
