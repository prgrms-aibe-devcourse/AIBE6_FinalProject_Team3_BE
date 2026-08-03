package com.algogyeyak.checklist.controller;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.checklist.dto.ChecklistItemUpdateRequest;
import com.algogyeyak.checklist.dto.ChecklistOverviewResponse;
import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.entity.ChecklistResult;
import com.algogyeyak.checklist.entity.ChecklistStatus;
import com.algogyeyak.checklist.service.ChecklistService;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ChecklistController")
class ChecklistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ChecklistService checklistService;

    // 이 테스트는 checklist 도메인 동작만 검증하고 blacklist 자체는 다루지 않으므로, 실제 Redis 대신
    // mock으로 대체한다(mock 기본값 false = "블랙리스트에 없음"이라 정상 인증 흐름을 그대로 탄다).
    @MockitoBean
    private AccessTokenRevocationService accessTokenRevocationService;

    @Test
    @DisplayName("인증된 사용자가 매물 체크리스트 생성을 요청하면 생성된 체크리스트를 반환한다")
    void createChecklistReturnsChecklistForAuthenticatedUser() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img");
        ReflectionTestUtils.setField(user, "id", 1L);

        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(1)
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true)
                .build();
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", 10L);
        Checklist checklist = Checklist.createFrom(user, property, 1, List.of(template));
        when(checklistService.createOrGetChecklist(eq(1L), eq(10L))).thenReturn(checklist);

        mockMvc.perform(post("/properties/10/checklists")
                        .cookie(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.propertyId").value(10))
                .andExpect(jsonPath("$.data.templateVersion").value(1))
                .andExpect(jsonPath("$.data.items[0].content").value("누수 확인"));
    }

    @Test
    @DisplayName("인증 토큰 없이 요청하면 401을 반환한다")
    void createChecklistRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(post("/properties/10/checklists"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("인증된 사용자가 항목을 미흡으로 표시하면 갱신된 항목을 반환한다")
    void updateChecklistItemMarksInsufficient() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);

        ChecklistItem item = ChecklistItem.builder()
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .build();
        ReflectionTestUtils.setField(item, "id", 200L);
        ReflectionTestUtils.setField(item, "checked", true);
        ReflectionTestUtils.setField(item, "userNote", "환기구 막힘");

        when(checklistService.updateChecklistItem(eq(1L), eq(100L), eq(200L), any(ChecklistItemUpdateRequest.class)))
                .thenReturn(item);

        mockMvc.perform(patch("/checklists/100/items/200")
                        .cookie(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChecklistItemUpdateRequest(null, null, "환기구 막힘"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.checked").value(true))
                .andExpect(jsonPath("$.data.userNote").value("환기구 막힘"))
                .andExpect(jsonPath("$.data.issueFound").value(true));
    }

    @Test
    @DisplayName("인증 토큰 없이 항목 수정을 요청하면 401을 반환한다")
    void updateChecklistItemRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(patch("/checklists/100/items/200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChecklistItemUpdateRequest(true, null, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("인증된 사용자가 체크리스트 결과를 조회하면 결과를 반환한다")
    void getChecklistResultReturnsResult() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        ChecklistResult result = new ChecklistResult(ChecklistStatus.IN_PROGRESS, 1, 2, 1, 0, null);
        when(checklistService.getChecklistResult(eq(1L), eq(100L))).thenReturn(result);

        mockMvc.perform(get("/checklists/100/result")
                        .cookie(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.checkedCount").value(1))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.requiredMissingCount").value(1))
                .andExpect(jsonPath("$.data.issueCount").value(0))
                .andExpect(jsonPath("$.data.message").doesNotExist())
                .andExpect(jsonPath("$.data.disclaimer").value("이 결과는 매물의 안전을 보장하지 않습니다."));
    }

    @Test
    @DisplayName("체크리스트를 시작하지 않았으면 시작 안내 메시지를 함께 반환한다")
    void getChecklistResultReturnsStartMessageWhenNotStarted() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        ChecklistResult result = new ChecklistResult(ChecklistStatus.NOT_STARTED, 0, 2, 2, 0, "체크리스트를 시작해보세요");
        when(checklistService.getChecklistResult(eq(1L), eq(100L))).thenReturn(result);

        mockMvc.perform(get("/checklists/100/result")
                        .cookie(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.message").value("체크리스트를 시작해보세요"))
                .andExpect(jsonPath("$.data.disclaimer").value("이 결과는 매물의 안전을 보장하지 않습니다."));
    }

    @Test
    @DisplayName("인증 토큰 없이 결과를 조회하면 401을 반환한다")
    void getChecklistResultRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/checklists/100/result"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("인증된 사용자가 매물 체크리스트를 조회하면 문항까지 포함해 반환한다")
    void getChecklistReturnsChecklistForAuthenticatedUser() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img");
        ReflectionTestUtils.setField(user, "id", 1L);

        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(1)
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true)
                .build();
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", 10L);
        Checklist checklist = Checklist.createFrom(user, property, 1, List.of(template));
        when(checklistService.getChecklist(eq(1L), eq(10L))).thenReturn(checklist);

        mockMvc.perform(get("/properties/10/checklists")
                        .cookie(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.propertyId").value(10))
                .andExpect(jsonPath("$.data.items[0].content").value("누수 확인"));
    }

    @Test
    @DisplayName("인증 토큰 없이 체크리스트를 조회하면 401을 반환한다")
    void getChecklistRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/properties/10/checklists"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("인증된 사용자가 내 체크리스트 목록을 조회하면 매물별 현황을 반환한다")
    void listMyChecklistsReturnsOverviewForAuthenticatedUser() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        ChecklistOverviewResponse started = new ChecklistOverviewResponse(
                10L, 100L, "서울특별시 강남구 테헤란로 123", null, "OFFICETEL", "JEONSE", ChecklistStatus.IN_PROGRESS,
                java.time.LocalDateTime.of(2026, 7, 30, 10, 0)
        );
        ChecklistOverviewResponse notStarted = new ChecklistOverviewResponse(
                20L, null, "서울특별시 마포구 월드컵로 1", null, "MULTI_FAMILY", "MONTHLY_RENT", ChecklistStatus.NOT_STARTED,
                java.time.LocalDateTime.of(2026, 6, 1, 12, 0)
        );
        when(checklistService.listMyChecklists(1L)).thenReturn(List.of(started, notStarted));

        mockMvc.perform(get("/checklists")
                        .cookie(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].propertyId").value(10))
                .andExpect(jsonPath("$.data[0].checklistId").value(100))
                .andExpect(jsonPath("$.data[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data[1].propertyId").value(20))
                .andExpect(jsonPath("$.data[1].checklistId").doesNotExist())
                .andExpect(jsonPath("$.data[1].status").value("NOT_STARTED"));
    }

    @Test
    @DisplayName("인증 토큰 없이 내 체크리스트 목록을 조회하면 401을 반환한다")
    void listMyChecklistsRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/checklists"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }
}
