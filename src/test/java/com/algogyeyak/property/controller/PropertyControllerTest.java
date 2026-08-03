package com.algogyeyak.property.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
import com.algogyeyak.property.dto.PropertyDetailResponse;
import com.algogyeyak.property.dto.PropertyListResponse;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.dto.PropertySearchCondition;
import com.algogyeyak.property.dto.PropertyUpdateRequest;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.service.PropertyService;
import com.algogyeyak.user.enums.Role;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 컨트롤러 슬라이스 테스트. 이 슬라이스에는 실제 SecurityConfig(OAuth2/JWT)가 로드되지 않고
 * Boot의 기본 시큐리티 필터만 적용되는데, @AuthenticationPrincipal 해석은 이 필터가 SecurityContext를
 * 읽어야 동작하므로 addFilters = false로 끄면 안 된다 (껐더니 principal이 항상 null로 들어옴).
 * 대신 SecurityMockMvcRequestPostProcessors.authentication(...)으로 인증 정보를 주입해서 사용한다.
 */
@WebMvcTest(PropertyController.class)
@AutoConfigureMockMvc
class PropertyControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PropertyService propertyService;

    private RequestPostProcessor asUser(Long userId) {
        JwtUserPrincipal principal = new JwtUserPrincipal(userId, "test@example.com", Role.USER);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        return authentication(auth);
    }

    @Test
    void 매물_등록에_성공하면_201과_응답본문을_반환한다() throws Exception {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                "역세권 오피스텔",
                null
        );

        PropertyRegisterResponse response = new PropertyRegisterResponse(
                101L,
                "ACTIVE",
                new PropertyRegisterResponse.AddressResponse(
                        "서울특별시 강남구 테헤란로 123",
                        "서울특별시 강남구 역삼동 123-45",
                        37.4995539438207,
                        127.031393491745
                ),
                MarketComparisonResponse.unavailable("stub"),
                null
        );

        when(propertyService.register(anyLong(), any(PropertyRegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/properties")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.propertyId").value(101))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.address.roadAddress").value("서울특별시 강남구 테헤란로 123"));
    }

    @Test
    void 주소가_없으면_400을_반환한다() throws Exception {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                null,
                null
        );

        mockMvc.perform(post("/properties")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 매물_목록조회에_성공하면_200과_목록을_반환한다() throws Exception {
        PropertyListResponse item = new PropertyListResponse(
                101L,
                "OFFICETEL",
                "JEONSE",
                30_000_000L,
                null,
                23.5,
                "서울특별시 강남구 테헤란로 123",
                "서울특별시 강남구 역삼동 123-45",
                "ACTIVE",
                LocalDateTime.of(2026, 7, 23, 10, 0),
                75
        );

        Pageable pageable = PageRequest.of(0, 20);
        Page<PropertyListResponse> page = new PageImpl<>(List.of(item), pageable, 1);
        when(propertyService.getMyProperties(anyLong(), any(Pageable.class), any(PropertySearchCondition.class)))
                .thenReturn(PageResponse.from(page));

        mockMvc.perform(get("/properties")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].propertyId").value(101))
                .andExpect(jsonPath("$.data.content[0].roadAddress").value("서울특별시 강남구 테헤란로 123"))
                .andExpect(jsonPath("$.data.content[0].checklistProgress").value(75))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 매물_목록조회시_쿼리파라미터가_검색조건으로_전달된다() throws Exception {
        Pageable pageable = PageRequest.of(0, 20);
        Page<PropertyListResponse> page = new PageImpl<>(List.of(), pageable, 0);
        org.mockito.ArgumentCaptor<PropertySearchCondition> captor =
                org.mockito.ArgumentCaptor.forClass(PropertySearchCondition.class);
        when(propertyService.getMyProperties(anyLong(), any(Pageable.class), captor.capture()))
                .thenReturn(PageResponse.from(page));

        mockMvc.perform(get("/properties")
                        .queryParam("region", "역삼동")
                        .queryParam("minArea", "20")
                        .queryParam("maxArea", "30")
                        .queryParam("transactionType", "JEONSE")
                        .queryParam("propertyType", "OFFICETEL")
                        .queryParam("minDeposit", "10000000")
                        .queryParam("maxDeposit", "50000000")
                        .queryParam("minMonthlyRent", "300000")
                        .queryParam("maxMonthlyRent", "800000")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isOk());

        PropertySearchCondition captured = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(captured.region()).isEqualTo("역삼동");
        org.assertj.core.api.Assertions.assertThat(captured.minArea()).isEqualTo(20.0);
        org.assertj.core.api.Assertions.assertThat(captured.maxArea()).isEqualTo(30.0);
        org.assertj.core.api.Assertions.assertThat(captured.transactionType()).isEqualTo(TransactionType.JEONSE);
        org.assertj.core.api.Assertions.assertThat(captured.propertyType()).isEqualTo(PropertyType.OFFICETEL);
        org.assertj.core.api.Assertions.assertThat(captured.minDeposit()).isEqualTo(10_000_000L);
        org.assertj.core.api.Assertions.assertThat(captured.maxDeposit()).isEqualTo(50_000_000L);
        org.assertj.core.api.Assertions.assertThat(captured.minMonthlyRent()).isEqualTo(300_000L);
        org.assertj.core.api.Assertions.assertThat(captured.maxMonthlyRent()).isEqualTo(800_000L);
    }

    @Test
    void 허용되지_않은_정렬필드로_목록조회하면_400을_반환한다() throws Exception {
        when(propertyService.getMyProperties(anyLong(), any(Pageable.class), any(PropertySearchCondition.class)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_SORT_FIELD));

        mockMvc.perform(get("/properties")
                        .queryParam("sort", "userId,desc")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 매물_상세조회에_성공하면_200과_상세정보를_반환한다() throws Exception {
        PropertyDetailResponse response = new PropertyDetailResponse(
                101L,
                "OFFICETEL",
                "JEONSE",
                30_000_000L,
                null,
                23.5,
                "역세권 오피스텔",
                new PropertyDetailResponse.AddressResponse(
                        "서울특별시 강남구 테헤란로 123",
                        "서울특별시 강남구 역삼동 123-45",
                        37.4995539438207,
                        127.031393491745
                ),
                List.of("https://cdn.algogyeyak.com/img/abc.jpg"),
                MarketComparisonResponse.unavailable("stub"),
                false,
                false,
                "ACTIVE",
                LocalDateTime.of(2026, 7, 23, 10, 0),
                LocalDateTime.of(2026, 7, 23, 10, 0)
        );

        when(propertyService.getProperty(anyLong(), anyLong())).thenReturn(response);

        mockMvc.perform(get("/properties/101")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.description").value("역세권 오피스텔"))
                .andExpect(jsonPath("$.data.address.roadAddress").value("서울특별시 강남구 테헤란로 123"));
    }

    @Test
    void 존재하지_않는_매물을_상세조회하면_404를_반환한다() throws Exception {
        when(propertyService.getProperty(anyLong(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));

        mockMvc.perform(get("/properties/999")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 본인_소유가_아닌_매물을_상세조회하면_403을_반환한다() throws Exception {
        when(propertyService.getProperty(anyLong(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED));

        mockMvc.perform(get("/properties/101")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 매물_수정에_성공하면_200과_수정된_정보를_반환한다() throws Exception {
        PropertyUpdateRequest request = new PropertyUpdateRequest(35_000_000L, null, 25.0, "수정된 설명");

        PropertyDetailResponse response = new PropertyDetailResponse(
                101L,
                "OFFICETEL",
                "JEONSE",
                35_000_000L,
                null,
                25.0,
                "수정된 설명",
                new PropertyDetailResponse.AddressResponse(
                        "서울특별시 강남구 테헤란로 123",
                        "서울특별시 강남구 역삼동 123-45",
                        37.4995539438207,
                        127.031393491745
                ),
                List.of(),
                MarketComparisonResponse.unavailable("stub"),
                false,
                false,
                "ACTIVE",
                LocalDateTime.of(2026, 7, 23, 10, 0),
                LocalDateTime.of(2026, 7, 23, 11, 0)
        );

        when(propertyService.update(anyLong(), anyLong(), any(PropertyUpdateRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/properties/101")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deposit").value(35_000_000))
                .andExpect(jsonPath("$.data.description").value("수정된 설명"));
    }

    @Test
    void 존재하지_않는_매물을_수정하면_404를_반환한다() throws Exception {
        PropertyUpdateRequest request = new PropertyUpdateRequest(35_000_000L, null, 25.0, null);

        when(propertyService.update(anyLong(), anyLong(), any(PropertyUpdateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));

        mockMvc.perform(patch("/properties/999")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 본인_소유가_아닌_매물을_수정하면_403을_반환한다() throws Exception {
        PropertyUpdateRequest request = new PropertyUpdateRequest(35_000_000L, null, 25.0, null);

        when(propertyService.update(anyLong(), anyLong(), any(PropertyUpdateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED));

        mockMvc.perform(patch("/properties/101")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 보증금이_없으면_수정요청은_400을_반환한다() throws Exception {
        PropertyUpdateRequest request = new PropertyUpdateRequest(null, null, 25.0, null);

        mockMvc.perform(patch("/properties/101")
                        .with(asUser(USER_ID))
                        .with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 매물_삭제에_성공하면_200을_반환한다() throws Exception {
        doNothing().when(propertyService).delete(anyLong(), anyLong());

        mockMvc.perform(delete("/properties/101")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void 존재하지_않는_매물을_삭제하면_404를_반환한다() throws Exception {
        doThrow(new BusinessException(ErrorCode.PROPERTY_NOT_FOUND))
                .when(propertyService).delete(anyLong(), anyLong());

        mockMvc.perform(delete("/properties/999")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 본인_소유가_아닌_매물을_삭제하면_403을_반환한다() throws Exception {
        doThrow(new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED))
                .when(propertyService).delete(anyLong(), anyLong());

        mockMvc.perform(delete("/properties/101")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 이미_삭제된_매물을_다시_삭제하면_409를_반환한다() throws Exception {
        doThrow(new BusinessException(ErrorCode.PROPERTY_ALREADY_DELETED))
                .when(propertyService).delete(anyLong(), anyLong());

        mockMvc.perform(delete("/properties/101")
                        .with(asUser(USER_ID))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }
}
