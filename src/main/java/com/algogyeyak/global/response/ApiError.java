package com.algogyeyak.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String fallback
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null, null);
    }

    public static ApiError of(String code, String message, List<FieldError> fieldErrors) {
        return new ApiError(code, message, fieldErrors, null);
    }

    public static ApiError ofFallback(String code, String message, String fallback) {
        return new ApiError(code, message, null, fallback);
    }

    public record FieldError(
            String field,
            String message
    ) {
    }
}
