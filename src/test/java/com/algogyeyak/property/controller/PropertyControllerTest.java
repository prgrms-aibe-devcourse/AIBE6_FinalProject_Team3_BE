package com.algogyeyak.property.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.dto.PropertyDetailResponse;
import com.algogyeyak.property.dto.PropertyListResponse;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.service.PropertyService;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 컨트롤러 슬라이스 테스트. 인증(Security) 도메인이 아직 없어 addFilters = false로
 * 시큐리티 필터를 끄고 컨트롤러 동작만 검증한다. 실제 인증 붙으면 이 부분 재검토 필요.
 */
@WebMvcTest(PropertyController.class)
@AutoConfigureMockMvc(addFilters = false)
class PropertyControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PropertyService propertyService;

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
                PropertyRegisterResponse.MarketComparisonResponse.unavailable(),
                null
        );

        when(propertyService.register(anyLong(), any(PropertyRegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/properties")
                        .header("X-User-Id", 1L)
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
                        .header("X-User-Id", 1L)
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
                LocalDateTime.of(2026, 7, 23, 10, 0)
        );

        when(propertyService.getMyProperties(anyLong())).thenReturn(List.of(item));

        mockMvc.perform(get("/properties")
                        .header("X-User-Id", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].propertyId").value(101))
                .andExpect(jsonPath("$.data[0].roadAddress").value("서울특별시 강남구 테헤란로 123"));
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
                PropertyDetailResponse.MarketComparisonResponse.unavailable(),
                "ACTIVE",
                LocalDateTime.of(2026, 7, 23, 10, 0),
                LocalDateTime.of(2026, 7, 23, 10, 0)
        );

        when(propertyService.getProperty(anyLong(), anyLong())).thenReturn(response);

        mockMvc.perform(get("/properties/101")
                        .header("X-User-Id", 1L))
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
                        .header("X-User-Id", 1L))
                .andExpect(status().isNotFound());
    }

    @Test
    void 본인_소유가_아닌_매물을_상세조회하면_403을_반환한다() throws Exception {
        when(propertyService.getProperty(anyLong(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED));

        mockMvc.perform(get("/properties/101")
                        .header("X-User-Id", 1L))
                .andExpect(status().isForbidden());
    }
}
