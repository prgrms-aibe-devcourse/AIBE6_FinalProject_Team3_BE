package com.algogyeyak.property.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.service.PropertyService;
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
}
