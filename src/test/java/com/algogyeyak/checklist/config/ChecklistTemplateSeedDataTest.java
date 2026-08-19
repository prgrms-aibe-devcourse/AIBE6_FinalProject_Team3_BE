package com.algogyeyak.checklist.config;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.property.entity.PropertyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChecklistTemplateSeedData")
class ChecklistTemplateSeedDataTest {

    private final List<ChecklistItemTemplate> templates = ChecklistTemplateSeedData.initialTemplates();

    // (2026-08-14 갱신) 실사용 피드백(누수/콘센트/누전/차단기 확인 방법 구체화, 보안·단열 항목 보완)을
    // 반영해 버전 3에서 20~24개였던 상한이 20~30개로 늘어났다 - 세분화된 항목을 병합해 개수를
    // 맞추기보다(예: 콘센트+누전+차단기를 하나로 합치면 "어느 부분이 문제인지" 구분이 안 됨),
    // 항목 수 증가를 감수하고 명확성을 우선하기로 결정.
    @Test
    @DisplayName("매물유형별로 적용되는 문항 수는 20~30개다 (전체 템플릿 목록은 매물유형별 변형 문항 때문에 그보다 많을 수 있다)")
    void eachPropertyTypeHasBetween20And30ApplicableItems() {
        for (PropertyType propertyType : PropertyType.values()) {
            long applicableCount = templates.stream()
                    .filter(template -> template.isApplicableTo(propertyType))
                    .count();
            assertThat(applicableCount).as("propertyType=%s", propertyType).isBetween(20L, 30L);
        }
    }

    @Test
    @DisplayName("5개 카테고리(실내/소음/보안/서류/주변)를 모두 포함한다")
    void coversAllFiveCategories() {
        Map<ChecklistCategory, Long> countByCategory = templates.stream()
                .collect(Collectors.groupingBy(ChecklistItemTemplate::getCategory, Collectors.counting()));

        assertThat(countByCategory).containsOnlyKeys(
                ChecklistCategory.INDOOR, ChecklistCategory.NOISE, ChecklistCategory.SAFETY,
                ChecklistCategory.DOCUMENTS, ChecklistCategory.AREA
        );
    }

    @Test
    @DisplayName("자동 issueFound 규칙이 붙는 6개 code가 각각 정확히 1개씩 존재한다")
    void containsEachRuleCodeExactlyOnce() {
        List<ChecklistItemCode> expectedCodes = List.of(
                ChecklistItemCode.TRUST_REGISTRATION,
                ChecklistItemCode.OWNERSHIP_MATCH,
                ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE,
                ChecklistItemCode.TAX_DELINQUENCY_NOTICE,
                ChecklistItemCode.DATE_OF_CONFIRMATION_REQUEST,
                ChecklistItemCode.RESIDENT_REGISTRATION_REQUEST
        );

        for (ChecklistItemCode code : expectedCodes) {
            long count = templates.stream().filter(t -> t.getCode() == code).count();
            assertThat(count).as("code=%s", code).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("모든 문항은 버전 3, active=true로 생성된다")
    void allItemsAreVersionThreeAndActive() {
        assertThat(templates).allSatisfy(template -> {
            assertThat(template.getVersion()).isEqualTo(3);
            assertThat(template.isActive()).isTrue();
        });
    }

    @Test
    @DisplayName("(2026-08-14 신규) 단창/이중창·누전·차단기·보일러종류·냉난방방식 문항이 포함된다")
    void containsNewlyAddedItems() {
        List<String> expectedNewContents = List.of(
                "창문이 이중창(두 겹 유리)인가요?",
                "콘센트·전기 배선에 누전 위험이 없나요?",
                "차단기함 상태를 확인했나요?",
                "보일러 종류가 무엇인가요?",
                "냉난방 방식이 무엇인가요?"
        );
        for (String content : expectedNewContents) {
            assertThat(templates.stream().anyMatch(t -> t.getContent().equals(content)))
                    .as("content=%s", content)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("(2026-08-14 갱신) 외부 소음 문항은 저층/고층 케이스를 구분한 guideText를 갖는다")
    void outdoorNoiseItemHasFloorAwareGuideText() {
        ChecklistItemTemplate outdoorNoise = templates.stream()
                .filter(t -> t.getContent().equals("외부 소음(도로·상가 등)이 심하지 않나요?"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("외부 소음 문항을 찾을 수 없음"));

        assertThat(outdoorNoise.getGuideText()).contains("저층").contains("고층");
    }

    @Test
    @DisplayName("(2026-08-14 신규) 방범창 문항은 연립다세대·단독다가구에만 적용된다")
    void securityWindowBarsItemOnlyAppliesToNonOfficetel() {
        ChecklistItemTemplate securityBars = templates.stream()
                .filter(t -> t.getContent().contains("방범창"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("방범창 문항을 찾을 수 없음"));

        assertThat(securityBars.isApplicableTo(PropertyType.OFFICETEL)).isFalse();
        assertThat(securityBars.isApplicableTo(PropertyType.MULTI_FAMILY)).isTrue();
        assertThat(securityBars.isApplicableTo(PropertyType.DETACHED_HOUSE)).isTrue();
    }

    @Test
    @DisplayName("(2026-08-14 신규) 보일러 종류/냉난방 방식은 MULTIPLE_CHOICE 타입이고 선택지를 갖는다")
    void newMultipleChoiceItemsHaveOptions() {
        ChecklistItemTemplate boilerType = templates.stream()
                .filter(t -> t.getContent().equals("보일러 종류가 무엇인가요?"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("보일러 종류 문항을 찾을 수 없음"));
        ChecklistItemTemplate heatingType = templates.stream()
                .filter(t -> t.getContent().equals("냉난방 방식이 무엇인가요?"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("냉난방 방식 문항을 찾을 수 없음"));

        assertThat(boilerType.getItemType()).isEqualTo(ChecklistItemType.MULTIPLE_CHOICE);
        assertThat(boilerType.getOptions()).isEqualTo("가스보일러,기름보일러,전기보일러,지역난방");
        assertThat(heatingType.getItemType()).isEqualTo(ChecklistItemType.MULTIPLE_CHOICE);
        assertThat(heatingType.getOptions()).isEqualTo("중앙난방,개별난방,지역난방");
    }

    @Test
    @DisplayName("서류·행정 REQUIRED 6개 문항은 초등학생도 이해할 수 있는 helperText를 갖는다")
    void requiredDocumentItemsHaveHelperText() {
        Map<String, String> expectedHelperTextByContent = Map.of(
                "등기부등본을 확인했나요?",
                "등기부등본은 이 집이 진짜 누구 것인지, 빚(대출)이 얼마나 있는지 나라에서 보여주는 서류예요.\n\n"
                        + "인터넷등기소(iros.go.kr)에서 적은 돈(700원 정도)만 내면 바로 확인할 수 있어요.\n\n"
                        + "계약 전에 꼭 확인해서, **지금 계약하려는 사람이 진짜 주인이 맞는지**, 빚이 너무 많지는 않은지 봐야 해요.",
                "신탁등기가 되어 있나요?",
                "신탁등기는, 집주인이 자기 집을 신탁회사라는 곳에 '대신 맡아서 관리해줘'라고 맡겨놓은 거예요. "
                        + "여행 갈 때 친구한테 '내 물건 좀 봐줘'라고 맡기는 것과 비슷해요.\n\n"
                        + "이렇게 맡기고 나면, 서류상으로는 신탁회사가 진짜 주인이 돼요.\n\n"
                        + "그래서 원래 집주인이 세입자랑 계약을 해도, 진짜 주인 역할을 하는 신탁회사가 '그 계약 괜찮아요'라고 허락해줘야 안전해요. "
                        + "허락 없이 계약하면 나중에 신탁회사가 '난 모르는 계약이에요'라고 인정 안 해줄 수 있고, 그럼 **보증금을 못 돌려받을 수도 있어요**.",
                "등기부등본상 소유자와 임대인(계약 당사자)의 명의가 다른가요?",
                "등기부등본에 적힌 진짜 집주인이랑, 지금 나랑 계약하는 사람이 다른 경우예요.\n\n"
                        + "이럴 땐 그 사람이 진짜 주인 허락 없이 마음대로 계약하는 걸 수도 있어서, 나중에 진짜 주인이 '난 그런 계약 몰라요'라고 하면 계약이 취소되고 **보증금을 못 돌려받을 수 있어요**.\n\n"
                        + "그래서 명의가 다를 땐, 진짜 주인이 '이 사람이 대신 계약해도 돼요'라고 써준 **서류(위임장)가 있는지 꼭 확인**해야 해요.",
                "소유권 취득일을 확인했나요?",
                "얼마 전에 주인이 바뀐 집은 조금 더 조심해야 해요.\n\n"
                        + "만약 지금 주인이 대출을 많이 받아서 이 집을 산 거라면, 나중에 대출을 못 갚아서 집이 경매(강제로 팔리는 것)에 넘어갈 수 있어요. "
                        + "그러면 세입자는 **보증금을 못 돌려받을 수도 있어요**.\n\n"
                        + "그래서 언제 지금 주인이 이 집을 샀는지 확인해두면 좋아요.",
                "확정일자 부여현황을 임대인에게 요청했나요?",
                "확정일자는 계약서에 '언제 이 집을 계약했는지' 도장 찍듯이 나라에서 증명해주는 거예요.\n\n"
                        + "이 집이 나중에 경매(강제로 팔리는 것)에 넘어가면, 확정일자를 먼저 받은 사람 순서대로 돈을 먼저 받아가요. "
                        + "'선순위 보증금'은 나보다 먼저 확정일자를 받아서 나보다 먼저 돈을 받아갈 사람들의 보증금을 다 합친 금액이에요.\n\n"
                        + "이게 너무 많으면, 집을 팔아도 내 보증금을 돌려줄 돈이 안 남을 수 있어요. "
                        + "그래서 계약 전에 임대인한테 **'확정일자 부여현황'이라는 서류를 달라고 해서**, 나보다 먼저 돈 받아갈 사람이 얼마나 있는지 미리 확인해야 해요.",
                "전입세대열람원을 임대인에게 요청했나요?",
                "전입세대열람원은 그 집에 누가 살고 있다고 나라에 신고했는지 보여주는 서류예요.\n\n"
                        + "계약서를 가지고 가까운 주민센터(행정복지센터)에 가면 발급받을 수 있고, 정부24 홈페이지에서도 가능해요.\n\n"
                        + "이 서류가 필요한 이유는, 나 말고 이미 그 집에 산다고 신고한 다른 사람이 있으면 **그 사람이 나보다 먼저 보증금을 받아갈 수도 있기 때문**이에요. "
                        + "그래서 계약 전에 미리 확인해두는 게 안전해요."
        );

        expectedHelperTextByContent.forEach((content, expectedHelperText) -> {
            ChecklistItemTemplate template = templates.stream()
                    .filter(t -> t.getContent().equals(content))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("문항을 찾을 수 없음: " + content));
            assertThat(template.getHelperText()).as("content=%s", content).isEqualTo(expectedHelperText);
        });
    }
}
