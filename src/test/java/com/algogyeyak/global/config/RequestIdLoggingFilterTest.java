package com.algogyeyak.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class RequestIdLoggingFilterTest {

    private final RequestIdLoggingFilter filter = new RequestIdLoggingFilter();

    @Test
    void 요청에_X_Request_Id_헤더가_없으면_새로_발급해서_MDC와_응답헤더에_반영한다() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdLoggingFilter.REQUEST_ID_HEADER)).thenReturn(null);

        String[] mdcValueDuringChain = new String[1];
        doAnswer(invocation -> {
            mdcValueDuringChain[0] = MDC.get(RequestIdLoggingFilter.MDC_KEY);
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(mdcValueDuringChain[0]).isNotBlank();
        verify(response).setHeader(RequestIdLoggingFilter.REQUEST_ID_HEADER, mdcValueDuringChain[0]);
        // 요청이 끝나면 스레드풀 재사용에 대비해 MDC를 반드시 지운다.
        assertThat(MDC.get(RequestIdLoggingFilter.MDC_KEY)).isNull();
    }

    @Test
    void 요청에_X_Request_Id_헤더가_있으면_그_값을_그대로_이어받는다() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdLoggingFilter.REQUEST_ID_HEADER)).thenReturn("upstream-request-id-123");

        filter.doFilter(request, response, chain);

        verify(response).setHeader(RequestIdLoggingFilter.REQUEST_ID_HEADER, "upstream-request-id-123");
    }

    @Test
    void 체인_중간에_예외가_나도_MDC는_반드시_정리된다() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdLoggingFilter.REQUEST_ID_HEADER)).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            doAnswer(invocation -> {
                throw new IllegalStateException("boom");
            }).when(chain).doFilter(any(), any());
            filter.doFilter(request, response, chain);
        }).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(RequestIdLoggingFilter.MDC_KEY)).isNull();
    }
}
