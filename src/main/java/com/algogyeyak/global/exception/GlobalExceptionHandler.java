package com.algogyeyak.global.exception;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.response.ApiError;
import com.algogyeyak.global.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ApiError error = exception.getFallback() != null
                ? ApiError.ofFallback(exception.getErrorCode().getCode(), exception.getMessage(), exception.getFallback())
                : ApiError.of(exception.getErrorCode().getCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus()).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        List<ApiError.FieldError> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fieldError -> new ApiError.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        return buildErrorResponse(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.getMessage(), fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException exception) {
        List<ApiError.FieldError> fieldErrors = exception.getConstraintViolations()
                .stream()
                .map(violation -> new ApiError.FieldError(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()
                ))
                .toList();

        return buildErrorResponse(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.getMessage(), fieldErrors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException exception
    ) {
        String message = exception.getParameterName() + " 파라미터는 필수입니다.";
        return buildErrorResponse(ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestPartException(
            MissingServletRequestPartException exception
    ) {
        String message = exception.getRequestPartName() + " 파트는 필수입니다.";
        return buildErrorResponse(ErrorCode.BAD_REQUEST, message);
    }

    // spring.servlet.multipart.max-file-size/max-request-size(둘 다 10MB)를 넘는 요청은
    // 컨트롤러/서비스 코드(예: ContractAnalysisOcrService의 자체 10MB 체크)에 도달하기도 전에
    // 멀티파트 리졸버가 이 예외를 던진다 - 핸들러가 없으면 catch-all Exception으로 떨어져
    // 500이 나간다. 현재 멀티파트 업로드를 직접 받는 곳이 contract-analysis(/inputs, /ocr)뿐이라
    // CONTRACT_ANALYSIS_FILE_TOO_LARGE로 매핑한다 - 다른 도메인이 멀티파트 업로드를 추가하면
    // 그때 이 매핑을 다시 검토해야 한다.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException exception
    ) {
        return buildErrorResponse(
                ErrorCode.CONTRACT_ANALYSIS_FILE_TOO_LARGE, ErrorCode.CONTRACT_ANALYSIS_FILE_TOO_LARGE.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception
    ) {
        return buildErrorResponse(ErrorCode.BAD_REQUEST, "요청 본문을 읽을 수 없습니다.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception
    ) {
        String message = exception.getName() + " 파라미터의 값이 올바르지 않습니다.";
        return buildErrorResponse(ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiResponse<Void>> handlePropertyReferenceException(PropertyReferenceException exception) {
        return buildErrorResponse(ErrorCode.INVALID_SORT_FIELD, ErrorCode.INVALID_SORT_FIELD.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException exception) {
        return buildErrorResponse(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException exception) {
        return buildErrorResponse(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException exception
    ) {
        return buildErrorResponse(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException exception
    ) {
        return buildErrorResponse(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.getMessage());
    }

    // 매핑된 컨트롤러가 없는 경로(예: 프론트가 아직 구현 안 된 API를 호출하거나 오타난 경로로
    // 요청한 경우) 요청이면 Spring이 정적 리소스로도 못 찾았다는 뜻으로 이 예외를 던진다 - 이걸
    // 아래 catch-all(Exception.class)이 잡으면 단순 404가 500 INTERNAL_SERVER_ERROR로 둔갑해
    // 로그/모니터링에서 실제 서버 장애처럼 보인다.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException exception) {
        return buildErrorResponse(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        log.error("Unhandled exception", exception);
        return buildErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> buildErrorResponse(ErrorCode errorCode, String message) {
        return buildErrorResponse(errorCode, message, null);
    }

    private ResponseEntity<ApiResponse<Void>> buildErrorResponse(
            ErrorCode errorCode,
            String message,
            List<ApiError.FieldError> fieldErrors
    ) {
        ApiError error = ApiError.of(errorCode.getCode(), message, fieldErrors);
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.failure(error));
    }
}
