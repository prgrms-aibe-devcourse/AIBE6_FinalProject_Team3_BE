package com.algogyeyak.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionResponse() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.NOT_FOUND, "매물을 찾을 수 없습니다.")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().error().message()).isEqualTo("매물을 찾을 수 없습니다.");
    }

    @Test
    void unhandledExceptionResponse() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleException(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().error().message()).isEqualTo("서버 내부 오류가 발생했습니다.");
    }
}
