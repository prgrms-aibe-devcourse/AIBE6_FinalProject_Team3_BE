package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChecklistResponse")
class ChecklistResponseTest {

    private User testUser() {
        return User.createOAuthUser("test@example.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");
    }

    private Property testProperty(Long id) {
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }

    private ChecklistItemTemplate template(
            ChecklistCategory category,
            String content,
            ChecklistImportance importance,
            int displayOrder
    ) {
        return ChecklistItemTemplate.builder()
                .version(1)
                .category(category)
                .content(content)
                .importance(importance)
                .itemType(ChecklistItemType.CHECK)
                .code((ChecklistItemCode) null)
                .displayOrder(displayOrder)
                .active(true)
                .build();
    }

    @Test
    @DisplayName("from()은 문항을 카테고리 -> 중요도(REQUIRED 우선) -> displayOrder 순으로 정렬해서 담는다")
    void fromSortsItemsByCategoryThenImportanceThenDisplayOrder() {
        // 일부러 뒤죽박죽인 순서로 템플릿을 만든다 (여러 카테고리가 섞이고, displayOrder도 정렬 안 된 상태)
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.DOCUMENTS, "A-서류-일반-10", ChecklistImportance.GENERAL, 10),
                template(ChecklistCategory.INDOOR, "B-실내-일반-5", ChecklistImportance.GENERAL, 5),
                template(ChecklistCategory.DOCUMENTS, "C-서류-필수-1", ChecklistImportance.REQUIRED, 1),
                template(ChecklistCategory.INDOOR, "D-실내-일반-1", ChecklistImportance.GENERAL, 1),
                template(ChecklistCategory.AREA, "E-주변-일반-1", ChecklistImportance.GENERAL, 1)
        );
        Checklist checklist = Checklist.createFrom(testUser(), testProperty(10L), 1, templates);

        ChecklistResponse response = ChecklistResponse.from(checklist);

        // FE(Frontend/app/data/checklist.ts)가 서류·행정을 의도적으로 맨 마지막에 두므로
        // (REQUIRED 항목이 몰려 있어, 다른 탭을 다 보기 전에 "다음 단계" 버튼이 뜨는 걸 막기 위함) 백엔드도 이 순서를 따른다.
        assertThat(response.items())
                .extracting(ChecklistItemResponse::content)
                .containsExactly(
                        "D-실내-일반-1",   // INDOOR, displayOrder 1
                        "B-실내-일반-5",   // INDOOR, displayOrder 5
                        "E-주변-일반-1",   // AREA
                        "C-서류-필수-1",   // DOCUMENTS(맨 마지막), REQUIRED가 GENERAL보다 먼저
                        "A-서류-일반-10"   // DOCUMENTS, GENERAL
                );
    }

    @Test
    @DisplayName("from()은 문항의 helperText를 그대로 담는다")
    void fromIncludesHelperText() {
        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(1)
                .category(ChecklistCategory.DOCUMENTS)
                .content("등기부등본을 확인했나요?")
                .helperText("등기부등본은 이 집이 진짜 누구 것인지 보여주는 서류예요.")
                .importance(ChecklistImportance.REQUIRED)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true)
                .build();
        Checklist checklist = Checklist.createFrom(testUser(), testProperty(10L), 1, List.of(template));

        ChecklistResponse response = ChecklistResponse.from(checklist);

        assertThat(response.items().get(0).helperText())
                .isEqualTo("등기부등본은 이 집이 진짜 누구 것인지 보여주는 서류예요.");
    }
}
