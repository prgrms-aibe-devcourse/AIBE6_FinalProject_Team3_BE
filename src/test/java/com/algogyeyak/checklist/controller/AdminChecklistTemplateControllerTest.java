package com.algogyeyak.checklist.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.testsupport.CsrfHeaderMockMvcCustomizer;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AdminUserControllerTest와 동일한 패턴(@SpringBootTest + 실제 JwtProvider로 발급한 access_token 쿠키) -
 * SecurityConfig의 requestMatchers("/admin/**").hasRole("ADMIN")이 실제로 걸려있는지까지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CsrfHeaderMockMvcCustomizer.class)
class AdminChecklistTemplateControllerTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long USER_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ChecklistItemTemplateRepository checklistItemTemplateRepository;

    // 이 테스트는 관리자 CRUD 로직만 검증하고 blacklist 자체는 다루지 않으므로, 실제 Redis 대신
    // mock으로 대체한다(mock 기본값 false = "블랙리스트에 없음"이라 정상 인증 흐름을 그대로 탄다).
    @MockitoBean
    private AccessTokenRevocationService accessTokenRevocationService;

    private Cookie adminCookie() {
        String token = jwtProvider.createAccessToken(ADMIN_ID, "admin@example.com", Role.ADMIN);
        return new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token);
    }

    private Cookie userCookie() {
        String token = jwtProvider.createAccessToken(USER_ID, "user@example.com", Role.USER);
        return new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token);
    }

    private User buildUser(Long id, String email, String nickname, Role role) {
        User user = User.createLocalUser(email, "hash", nickname);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "role", role);
        return user;
    }

    private ChecklistItemTemplate template(Long id) {
        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(2)
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true)
                .build();
        ReflectionTestUtils.setField(template, "id", id);
        return template;
    }

    // JwtAuthenticationFilter가 매 요청 DB에서 유저 상태/권한을 재확인하므로, 토큰의 주체에 대한
    // 활성 유저 스텁이 없으면 인증 자체가 실패해 각 테스트의 실제 목적(200/403/404 등)을 검증하기 전에
    // 401로 끝나버린다.
    @BeforeEach
    void stubAuthenticatedUsersForFilter() {
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(buildUser(ADMIN_ID, "admin@example.com", "관리자", Role.ADMIN)));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser(USER_ID, "user@example.com", "일반유저", Role.USER)));
    }

    @Test
    void 일반유저_토큰으로_접근하면_403이다() throws Exception {
        mockMvc.perform(get("/admin/checklist-templates").cookie(userCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 인증토큰_없이_접근하면_401이다() throws Exception {
        mockMvc.perform(get("/admin/checklist-templates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_토큰으로_목록조회에_성공한다() throws Exception {
        when(checklistItemTemplateRepository.findAllByOrderByDisplayOrderAsc())
                .thenReturn(List.of(template(10L)));

        mockMvc.perform(get("/admin/checklist-templates").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].content").value("누수 확인"));
    }

    @Test
    void 관리자_토큰으로_문항생성에_성공한다() throws Exception {
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(template(10L)));
        // 실제 JPA save()는 IDENTITY 전략이라 저장 즉시 생성된 id를 채워 리턴한다 - 이 mock도 그
        // 동작을 흉내내야 한다. AdminAuditLogger.log()가 saved.getId()를 targetId(nullable=false)로
        // 쓰는데, id가 안 채워진 채로 리턴하면(원래 mock처럼 인자를 그대로 돌려주기만 하면) 여기서
        // NULL 제약 위반이 난다.
        when(checklistItemTemplateRepository.save(any(ChecklistItemTemplate.class)))
                .thenAnswer(invocation -> {
                    ChecklistItemTemplate saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 11L);
                    return saved;
                });

        mockMvc.perform(post("/admin/checklist-templates")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"AREA",
                                  "content":"주차 공간이 충분한가요?",
                                  "importance":"GENERAL",
                                  "itemType":"CHECK",
                                  "displayOrder":30
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value("주차 공간이 충분한가요?"))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void 필수값이_없으면_문항생성이_400이다() throws Exception {
        mockMvc.perform(post("/admin/checklist-templates")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"AREA"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void guideText가_255자를_넘으면_문항생성이_400이다() throws Exception {
        String tooLong = "가".repeat(256);

        mockMvc.perform(post("/admin/checklist-templates")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"AREA",
                                  "content":"주차 공간이 충분한가요?",
                                  "guideText":"%s",
                                  "importance":"GENERAL",
                                  "itemType":"CHECK",
                                  "displayOrder":1
                                }
                                """.formatted(tooLong)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void applicablePropertyTypes가_255자를_넘으면_문항생성이_400이다() throws Exception {
        String tooLong = "OFFICETEL,".repeat(30);

        mockMvc.perform(post("/admin/checklist-templates")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"AREA",
                                  "content":"주차 공간이 충분한가요?",
                                  "importance":"GENERAL",
                                  "itemType":"CHECK",
                                  "displayOrder":1,
                                  "applicablePropertyTypes":"%s"
                                }
                                """.formatted(tooLong)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 존재하지_않는_매물유형으로_생성하면_400이다() throws Exception {
        mockMvc.perform(post("/admin/checklist-templates")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"AREA",
                                  "content":"주차 공간이 충분한가요?",
                                  "importance":"GENERAL",
                                  "itemType":"CHECK",
                                  "displayOrder":1,
                                  "applicablePropertyTypes":"APARTMENT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE"));
    }

    @Test
    void code에_맞지_않는_itemType으로_생성하면_400이다() throws Exception {
        mockMvc.perform(post("/admin/checklist-templates")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"DOCUMENTS",
                                  "content":"신탁등기가 되어 있나요?",
                                  "importance":"REQUIRED",
                                  "itemType":"CHECK",
                                  "code":"TRUST_REGISTRATION",
                                  "displayOrder":1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE"));
    }

    @Test
    void 이미_사용중인_code로_생성하면_409이다() throws Exception {
        ChecklistItemTemplate existing = template(10L);
        when(checklistItemTemplateRepository.findByCodeAndActiveTrue(com.algogyeyak.checklist.entity.ChecklistItemCode.TRUST_REGISTRATION))
                .thenReturn(List.of(existing));

        mockMvc.perform(post("/admin/checklist-templates")
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"DOCUMENTS",
                                  "content":"신탁등기가 되어 있나요? (중복)",
                                  "importance":"REQUIRED",
                                  "itemType":"YES_NO",
                                  "code":"TRUST_REGISTRATION",
                                  "displayOrder":1
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE"));
    }

    private String validCreateRequest() {
        return """
                {
                  "category":"AREA",
                  "content":"주차 공간이 충분한가요?",
                  "importance":"GENERAL",
                  "itemType":"CHECK",
                  "displayOrder":1
                }
                """;
    }

    @Test
    void 문항생성은_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(post("/admin/checklist-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 문항생성은_비관리자면_403이다() throws Exception {
        mockMvc.perform(post("/admin/checklist-templates")
                        .cookie(userCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 관리자_토큰으로_문항수정에_성공한다() throws Exception {
        ChecklistItemTemplate target = template(10L);
        when(checklistItemTemplateRepository.findById(10L)).thenReturn(Optional.of(target));
        // active=false로 바꾸는 요청이라, 마지막 활성 문항 보호 가드(AdminChecklistTemplateService.
        // validateNotDeactivatingLastActiveTemplate)에 걸리지 않도록 다른 활성 문항이 남아있는 것으로 스텁한다.
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(target, template(11L)));

        mockMvc.perform(patch("/admin/checklist-templates/{id}", 10L)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"SAFETY",
                                  "content":"창문 잠금장치가 정상 작동하나요?",
                                  "importance":"REQUIRED",
                                  "itemType":"CHECK",
                                  "displayOrder":9,
                                  "active":false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("SAFETY"))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void 존재하지_않는_문항수정은_404이다() throws Exception {
        when(checklistItemTemplateRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/admin/checklist-templates/{id}", 999L)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"SAFETY",
                                  "content":"창문 잠금장치가 정상 작동하나요?",
                                  "importance":"REQUIRED",
                                  "itemType":"CHECK",
                                  "displayOrder":9,
                                  "active":false
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND"));
    }

    @Test
    void code에_맞지_않는_itemType으로_수정하면_400이다() throws Exception {
        when(checklistItemTemplateRepository.findById(10L)).thenReturn(Optional.of(template(10L)));

        mockMvc.perform(patch("/admin/checklist-templates/{id}", 10L)
                        .cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"DOCUMENTS",
                                  "content":"신탁등기가 되어 있나요?",
                                  "importance":"REQUIRED",
                                  "itemType":"CHECK",
                                  "code":"TRUST_REGISTRATION",
                                  "displayOrder":1,
                                  "active":true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE"));
    }

    private String validUpdateRequest() {
        return """
                {
                  "category":"SAFETY",
                  "content":"창문 잠금장치가 정상 작동하나요?",
                  "importance":"REQUIRED",
                  "itemType":"CHECK",
                  "displayOrder":9,
                  "active":true
                }
                """;
    }

    @Test
    void 문항수정은_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(patch("/admin/checklist-templates/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 문항수정은_비관리자면_403이다() throws Exception {
        mockMvc.perform(patch("/admin/checklist-templates/{id}", 10L)
                        .cookie(userCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 관리자_토큰으로_문항삭제에_성공한다() throws Exception {
        ChecklistItemTemplate target = template(10L);
        when(checklistItemTemplateRepository.findById(10L)).thenReturn(Optional.of(target));
        when(checklistItemTemplateRepository.count()).thenReturn(2L);
        // 마지막 활성 문항 보호(validateNotDeactivatingLastActiveTemplate)에 걸리지 않도록 다른
        // 활성 문항이 남아있는 것으로 스텁한다.
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(target, template(11L)));

        mockMvc.perform(delete("/admin/checklist-templates/{id}", 10L).cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void 존재하지_않는_문항삭제는_404이다() throws Exception {
        when(checklistItemTemplateRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/admin/checklist-templates/{id}", 999L).cookie(adminCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND"));
    }

    @Test
    void 마지막_남은_문항삭제는_409이다() throws Exception {
        when(checklistItemTemplateRepository.findById(10L)).thenReturn(Optional.of(template(10L)));
        when(checklistItemTemplateRepository.count()).thenReturn(1L);

        mockMvc.perform(delete("/admin/checklist-templates/{id}", 10L).cookie(adminCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM"));
    }

    @Test
    void 문항삭제는_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(delete("/admin/checklist-templates/{id}", 10L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 문항삭제는_비관리자면_403이다() throws Exception {
        mockMvc.perform(delete("/admin/checklist-templates/{id}", 10L).cookie(userCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
