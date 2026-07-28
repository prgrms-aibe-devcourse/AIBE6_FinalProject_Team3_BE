package com.algogyeyak.checklist.config;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;

import java.util.ArrayList;
import java.util.List;

/**
 * 임장 체크리스트 템플릿(버전 2) 데이터. 기획서/요구사항 명세서 기준 24개 문항(매물유형별 1개 변형 적용 시), 5개 카테고리.
 * 매물유형별 분기는 템플릿 전체를 나누지 않고, 일부 문항만 {@link ChecklistItemTemplate#isApplicableTo}로 얇게 필터링한다
 * (v1 대비 변경점: 보안·안전의 "공동현관 잠금장치"를 매물유형별 변형 2개로 분리, 서류·행정에 "관리비 확인" 신규 추가).
 */
public final class ChecklistTemplateSeedData {

    private static final int VERSION = 2;

    private ChecklistTemplateSeedData() {
    }

    public static List<ChecklistItemTemplate> initialTemplates() {
        List<ChecklistItemTemplate> templates = new ArrayList<>();
        int order = 1;

        // 실내 상태 (5개)
        order = add(templates, ChecklistCategory.INDOOR, "벽면·천장·바닥에 누수 흔적이나 곰팡이가 없나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.INDOOR, "채광은 충분한가요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.INDOOR, "수압은 적절한가요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.INDOOR, "콘센트·전기 배선은 정상 작동하나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.INDOOR, "냉난방 시설은 정상 작동하나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);

        // 소음·환경 (3개)
        order = add(templates, ChecklistCategory.NOISE, "층간소음이 생활에 무리가 없는 수준인가요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.NOISE, "외부 소음(도로·상가 등)이 심하지 않나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.NOISE, "실내에 불쾌한 냄새가 없나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);

        // 보안·안전 (5개 - 잠금장치 문항은 매물유형별로 둘 중 하나만 적용됨, 실제로는 4개만 노출)
        // 오피스텔·연립다세대는 공동현관과 개별 현관문 잠금장치가 둘 다 있어 한 문항으로 같이 확인한다.
        order = add(templates, ChecklistCategory.SAFETY, "공동현관과 현관문 잠금장치가 모두 정상 작동하나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order, "OFFICETEL,MULTI_FAMILY");
        // 단독/다가구는 공동현관이 없을 수 있어(순수 단독주택) 현관문 기준으로만 확인한다.
        order = add(templates, ChecklistCategory.SAFETY, "현관문 잠금장치가 정상 작동하나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order, "DETACHED_HOUSE");
        order = add(templates, ChecklistCategory.SAFETY, "건물·주차장에 CCTV가 설치되어 있나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.SAFETY, "소화기·화재감지기가 비치되어 있나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);

        // 서류·행정 (9개) - 요구사항 명세서 기준 8개 + 관리비 확인(신규, 요구사항 명세서 반영 필요)
        order = add(templates, ChecklistCategory.DOCUMENTS, "등기부등본을 확인했나요?", null,
                ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요?",
                "신탁등기가 있으면 실소유자가 별도로 있어 계약 권한이 다를 수 있어요.",
                ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO, ChecklistItemCode.TRUST_REGISTRATION, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "등기부등본상 소유자와 임대인(계약 당사자)의 명의가 다른가요?",
                "명의가 다르면 위임장 등 대리 계약 권한을 별도로 확인해야 해요.",
                ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO, ChecklistItemCode.OWNERSHIP_MATCH, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "소유권 취득일을 확인했나요?",
                "최근에 소유권이 바뀐 집은, 전세가율이 이미 높은 경우 보증금 반환 위험이 더 커질 수 있어요.",
                ChecklistImportance.REQUIRED, ChecklistItemType.DATE, ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "임대인의 세금체납 여부를 확인해보셨나요?",
                "국세·지방세 완납증명서를 임대인에게 요청해 확인할 수 있어요 (자동 조회는 지원하지 않아요).",
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, ChecklistItemCode.TAX_DELINQUENCY_NOTICE, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "확정일자 부여현황을 임대인에게 요청했나요?",
                "선순위 보증금 규모를 확인하는 데 필요한 서류예요. 자동으로 조회되지 않아 직접 요청해야 해요.",
                ChecklistImportance.REQUIRED, ChecklistItemType.DOCUMENT_REQUEST, ChecklistItemCode.DATE_OF_CONFIRMATION_REQUEST, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "전입세대열람원을 임대인에게 요청했나요?",
                "다른 세입자의 전입 여부를 확인하는 서류예요. 자동으로 조회되지 않아 직접 요청해야 해요.",
                ChecklistImportance.REQUIRED, ChecklistItemType.DOCUMENT_REQUEST, ChecklistItemCode.RESIDENT_REGISTRATION_REQUEST, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "계약조건(특약사항 포함)을 다시 확인했나요?", null,
                ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "관리비에 전기세·수도세 등이 포함되어 있는지 확인했나요?",
                "관리비 명목으로 실제 임대료보다 부담이 커질 수 있어요. 포함 항목을 미리 확인하면 좋아요.",
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);

        // 주변 환경 (3개)
        order = add(templates, ChecklistCategory.AREA, "대중교통(지하철·버스) 이용이 편리한가요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.AREA, "편의시설(마트·병원 등)이 충분히 가까운가요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        add(templates, ChecklistCategory.AREA, "주차 공간이 충분한가요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);

        return templates;
    }

    private static int add(
            List<ChecklistItemTemplate> templates,
            ChecklistCategory category,
            String content,
            String guideText,
            ChecklistImportance importance,
            ChecklistItemType itemType,
            ChecklistItemCode code,
            int displayOrder
    ) {
        return add(templates, category, content, guideText, importance, itemType, code, displayOrder, null);
    }

    // 매물유형별로만 노출되는 문항(예: 공동현관/현관문 잠금장치)에 쓰는 오버로드.
    private static int add(
            List<ChecklistItemTemplate> templates,
            ChecklistCategory category,
            String content,
            String guideText,
            ChecklistImportance importance,
            ChecklistItemType itemType,
            ChecklistItemCode code,
            int displayOrder,
            String applicablePropertyTypes
    ) {
        templates.add(ChecklistItemTemplate.builder()
                .version(VERSION)
                .category(category)
                .content(content)
                .guideText(guideText)
                .importance(importance)
                .itemType(itemType)
                .code(code)
                .displayOrder(displayOrder)
                .active(true)
                .applicablePropertyTypes(applicablePropertyTypes)
                .build());
        return displayOrder + 1;
    }
}
