package com.algogyeyak.checklist.controller;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.service.ChecklistService;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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

    @MockitoBean
    private ChecklistService checklistService;

    @Test
    @DisplayName("인증된 사용자가 매물 체크리스트 생성을 요청하면 생성된 체크리스트를 반환한다")
    void createChecklistReturnsChecklistForAuthenticatedUser() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");
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
        Checklist checklist = Checklist.createFrom(user, 10L, 1, List.of(template));
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
}
