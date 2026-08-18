package com.algogyeyak.property.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import com.algogyeyak.property.dto.PropertyImageConfirmRequest;
import com.algogyeyak.property.dto.PropertyImageUploadUrlRequest;
import com.algogyeyak.user.enums.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * PropertyControllerTest와 동일한 이유로 addFilters=false를 쓰지 않는다 - @AuthenticationPrincipal
 * 해석에 SecurityContext가 필요하다.
 */
@WebMvcTest(PropertyImageUploadController.class)
@AutoConfigureMockMvc
class PropertyImageUploadControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private S3PresignService s3PresignService;

    private RequestPostProcessor asUser(Long userId) {
        JwtUserPrincipal principal = new JwtUserPrincipal(userId, "test@example.com", Role.USER, "테스트유저", null);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        return authentication(auth);
    }

    @Test
    void 업로드URL_발급에_성공하면_200과_uploadUrl_key를_반환한다() throws Exception {
        PropertyImageUploadUrlRequest request = new PropertyImageUploadUrlRequest("jpg", "image/jpeg", 1_000_000L);

        when(s3PresignService.generateUploadUrl(
                anyString(), eq("image/jpeg"), eq(1_000_000L), eq(S3ImagePurpose.PROPERTY)
        )).thenReturn("https://s3.example.com/presigned-put-url");

        mockMvc.perform(post("/properties/images/upload-url")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploadUrl").value("https://s3.example.com/presigned-put-url"))
                .andExpect(jsonPath("$.data.key").value(org.hamcrest.Matchers.startsWith("property-images/" + USER_ID + "/")))
                .andExpect(jsonPath("$.data.tagging").value(S3PresignService.PENDING_UPLOAD_TAG));
    }

    @Test
    void 파일크기가_없으면_400을_반환한다() throws Exception {
        String invalidJson = "{\"fileExtension\":\"jpg\",\"contentType\":\"image/jpeg\"}";

        mockMvc.perform(post("/properties/images/upload-url")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 업로드_확인에_성공하면_200과_imageUrl을_반환한다() throws Exception {
        String key = "property-images/1/abc.jpg";
        PropertyImageConfirmRequest request = new PropertyImageConfirmRequest(key);

        when(s3PresignService.generateDownloadUrl(key, S3ImagePurpose.PROPERTY))
                .thenReturn("https://cdn.example.com/" + key);

        mockMvc.perform(post("/properties/images/confirm")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/" + key));
    }

    @Test
    void 업로드가_완료되지_않았으면_확인에서_예외가_전파된다() throws Exception {
        String key = "property-images/1/missing.jpg";
        PropertyImageConfirmRequest request = new PropertyImageConfirmRequest(key);

        doThrow(new BusinessException(ErrorCode.FILE_UPLOAD_NOT_COMPLETED))
                .when(s3PresignService).confirmUpload(key, S3ImagePurpose.PROPERTY);

        mockMvc.perform(post("/properties/images/confirm")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // 다른 사용자 명의로 발급된 key(혹은 profile-images/, contract-images/ 같은 다른 도메인 key)를
    // 그대로 넘겨서 확정시키는 것을 막는지 확인한다 - isPropertyImageOwnedBy 검증이 s3PresignService
    // 호출보다 먼저 일어나야 하므로, 이 테스트는 confirmUpload가 아예 호출되지 않는 것까지 함께 검증한다.
    @Test
    void 다른_사용자의_key로_확인을_시도하면_403을_반환하고_S3_확인은_호출되지_않는다() throws Exception {
        String otherUsersKey = "property-images/999/abc.jpg";
        PropertyImageConfirmRequest request = new PropertyImageConfirmRequest(otherUsersKey);

        mockMvc.perform(post("/properties/images/confirm")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.FILE_KEY_ACCESS_DENIED.getCode()));

        org.mockito.Mockito.verify(s3PresignService, org.mockito.Mockito.never())
                .confirmUpload(anyString(), any());
    }

    @Test
    void 다른_도메인_key로_확인을_시도하면_403을_반환한다() throws Exception {
        String profileImageKey = "profile-images/1/abc.jpg";
        PropertyImageConfirmRequest request = new PropertyImageConfirmRequest(profileImageKey);

        mockMvc.perform(post("/properties/images/confirm")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.FILE_KEY_ACCESS_DENIED.getCode()));
    }
}
