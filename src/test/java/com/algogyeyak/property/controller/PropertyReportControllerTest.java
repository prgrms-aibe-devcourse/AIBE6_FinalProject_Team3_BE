package com.algogyeyak.property.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.dto.PropertyReportRequest;
import com.algogyeyak.property.dto.PropertyReportResponse;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.service.PropertyReportService;
import com.algogyeyak.user.enums.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(PropertyReportController.class)
@AutoConfigureMockMvc
class PropertyReportControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PropertyReportService propertyReportService;

    private RequestPostProcessor asUser(Long userId) {
        JwtUserPrincipal principal = new JwtUserPrincipal(userId, "test@example.com", Role.USER, "테스트유저", null);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        return authentication(auth);
    }

    @Test
    void 매물_신고에_성공하면_201과_응답본문을_반환한다() throws Exception {
        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.PRICE_MISMATCH, null);
        PropertyReportResponse response = new PropertyReportResponse(
                501L, 101L, "PRICE_MISMATCH", null, "RECEIVED", LocalDateTime.of(2026, 7, 23, 10, 15)
        );

        when(propertyReportService.report(anyLong(), anyLong(), any(PropertyReportRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/properties/101/reports")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reportId").value(501))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));
    }

    @Test
    void reason이_없으면_400을_반환한다() throws Exception {
        PropertyReportRequest request = new PropertyReportRequest(null, null);

        when(propertyReportService.report(anyLong(), anyLong(), any(PropertyReportRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.REPORT_REASON_REQUIRED));

        mockMvc.perform(post("/properties/101/reports")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REPORT_REASON_REQUIRED"));
    }

    @Test
    void ETC_사유인데_detail이_없으면_400을_반환한다() throws Exception {
        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.ETC, null);

        when(propertyReportService.report(anyLong(), anyLong(), any(PropertyReportRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.REPORT_DETAIL_REQUIRED));

        mockMvc.perform(post("/properties/101/reports")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REPORT_DETAIL_REQUIRED"));
    }

    @Test
    void 존재하지_않는_매물을_신고하면_404를_반환한다() throws Exception {
        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.PRICE_MISMATCH, null);

        when(propertyReportService.report(anyLong(), anyLong(), any(PropertyReportRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));

        mockMvc.perform(post("/properties/999/reports")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 본인_소유가_아닌_매물을_신고하면_403을_반환한다() throws Exception {
        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.PRICE_MISMATCH, null);

        when(propertyReportService.report(anyLong(), anyLong(), any(PropertyReportRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED));

        mockMvc.perform(post("/properties/101/reports")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 동일_매물을_중복_신고하면_409를_반환한다() throws Exception {
        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.PRICE_MISMATCH, null);

        when(propertyReportService.report(anyLong(), anyLong(), any(PropertyReportRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.REPORT_DUPLICATE));

        mockMvc.perform(post("/properties/101/reports")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REPORT_DUPLICATE"));
    }
}
