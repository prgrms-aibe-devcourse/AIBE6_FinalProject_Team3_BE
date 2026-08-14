package com.algogyeyak.checklist.config;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;

import java.util.ArrayList;
import java.util.List;

/**
 * 임장 체크리스트 템플릿(버전 3) 데이터. 매물유형별 분기는 템플릿 전체를 나누지 않고, 일부 문항만
 * {@link ChecklistItemTemplate#isApplicableTo}로 얇게 필터링한다
 * (v2 대비 변경점 - 2026-08-14, 실사용 피드백 반영: 단창/이중창·누전·차단기·보일러종류·냉난방방식
 * 5개 신규 추가 + 방범창(연립다세대·단독다가구 전용) 신규 추가. 매물유형별 적용 문항 수 상한이
 * 24개→30개로 늘어남 - ChecklistTemplateSeedDataTest 참고).
 */
public final class ChecklistTemplateSeedData {

    private static final int VERSION = 3;

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

        // (2026-08-14 신규) 단창/이중창·누전·차단기·보일러종류·냉난방방식 - 실사용 피드백(더 구체적인
        // 확인 항목 요청) 반영. 콘센트·배선(기존 항목)과 누전·차단기를 하나로 합치는 것도 고려했으나,
        // 합치면 "콘센트는 정상인데 차단기만 문제"인 상황을 하나의 체크로 구분할 수 없게 되어(어느
        // 부분이 문제인지 불명확), 항목 수가 늘어나더라도 각각 독립 항목으로 유지하기로 결정.
        order = add(templates, ChecklistCategory.INDOOR, "창문이 이중창(두 겹 유리)인가요?",
                "창문 옆면에서 유리가 몇 겹인지 보거나, 유리를 두드려보면 구분할 수 있어요. "
                        + "이중창은 유리 사이 공간 때문에 소리가 더 둔탁하고, 겨울철에 손을 대보면 단창보다 덜 차가워요.",
                ChecklistImportance.GENERAL, ChecklistItemType.YES_NO, null, order);
        order = add(templates, ChecklistCategory.INDOOR, "콘센트·전기 배선에 누전 위험이 없나요?",
                "콘센트나 스위치를 만졌을 때 찌릿한 느낌이 있는지, 차단기함의 '테스트' 버튼을 눌렀을 때 정상적으로 내려가는지 확인하세요.",
                "누전은 전기가 원래 흘러야 할 길이 아닌 곳으로 새는 걸 말해요. 눈으로 바로 보이진 않지만, "
                        + "콘센트를 만졌을 때 찌릿하거나 차단기가 자주 내려간다면 의심해볼 수 있어요.",
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.INDOOR, "차단기함 상태를 확인했나요?",
                "현관 신발장 근처에서 차단기함을 찾아 스위치가 모두 위쪽(정상)에 있는지 확인하세요.",
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order);
        order = addWithOptions(templates, ChecklistCategory.INDOOR, "보일러 종류가 무엇인가요?", null,
                ChecklistImportance.GENERAL, "가스보일러,기름보일러,전기보일러,지역난방", order);
        order = addWithOptions(templates, ChecklistCategory.INDOOR, "냉난방 방식이 무엇인가요?",
                "중앙난방은 건물 전체가 같이 켜지고 꺼지는 방식이라 개별 조절이 안 되고 운영 시간이 정해져 있을 수 있어요. "
                        + "개별난방은 세대별로 자유롭게 조절할 수 있어요.",
                ChecklistImportance.GENERAL, "중앙난방,개별난방,지역난방", order);

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
        // (2026-08-14 신규) 방범창은 저층 주택에서 더 의미 있는 항목이라 오피스텔은 제외하고
        // 연립다세대·단독다가구에만 노출한다.
        order = add(templates, ChecklistCategory.SAFETY, "방범창이 설치되어 있고 정상 작동하나요?", null,
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, order, "MULTI_FAMILY,DETACHED_HOUSE");

        // 서류·행정 (9개) - 요구사항 명세서 기준 8개 + 관리비 확인(신규, 요구사항 명세서 반영 필요)
        // REQUIRED 6개 문항은 guideText(실무 안내) 외에 helperText(부동산 지식 없는 사용자를 위한 풀어쓴 설명)도 함께 갖는다.
        order = add(templates, ChecklistCategory.DOCUMENTS, "등기부등본을 확인했나요?", null,
                "등기부등본은 이 집이 진짜 누구 것인지, 빚(대출)이 얼마나 있는지 나라에서 보여주는 서류예요.\n\n"
                        + "인터넷등기소(iros.go.kr)에서 적은 돈(700원 정도)만 내면 바로 확인할 수 있어요.\n\n"
                        + "계약 전에 꼭 확인해서, **지금 계약하려는 사람이 진짜 주인이 맞는지**, 빚이 너무 많지는 않은지 봐야 해요.",
                ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요?",
                "신탁등기가 있으면 실소유자가 별도로 있어 계약 권한이 다를 수 있어요.",
                "신탁등기는, 집주인이 자기 집을 신탁회사라는 곳에 '대신 맡아서 관리해줘'라고 맡겨놓은 거예요. "
                        + "여행 갈 때 친구한테 '내 물건 좀 봐줘'라고 맡기는 것과 비슷해요.\n\n"
                        + "이렇게 맡기고 나면, 서류상으로는 신탁회사가 진짜 주인이 돼요.\n\n"
                        + "그래서 원래 집주인이 세입자랑 계약을 해도, 진짜 주인 역할을 하는 신탁회사가 '그 계약 괜찮아요'라고 허락해줘야 안전해요. "
                        + "허락 없이 계약하면 나중에 신탁회사가 '난 모르는 계약이에요'라고 인정 안 해줄 수 있고, 그럼 **보증금을 못 돌려받을 수도 있어요**.",
                ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO, ChecklistItemCode.TRUST_REGISTRATION, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "등기부등본상 소유자와 임대인(계약 당사자)의 명의가 다른가요?",
                "명의가 다르면 위임장 등 대리 계약 권한을 별도로 확인해야 해요.",
                "등기부등본에 적힌 진짜 집주인이랑, 지금 나랑 계약하는 사람이 다른 경우예요.\n\n"
                        + "이럴 땐 그 사람이 진짜 주인 허락 없이 마음대로 계약하는 걸 수도 있어서, 나중에 진짜 주인이 '난 그런 계약 몰라요'라고 하면 계약이 취소되고 **보증금을 못 돌려받을 수 있어요**.\n\n"
                        + "그래서 명의가 다를 땐, 진짜 주인이 '이 사람이 대신 계약해도 돼요'라고 써준 **서류(위임장)가 있는지 꼭 확인**해야 해요.",
                ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO, ChecklistItemCode.OWNERSHIP_MATCH, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "소유권 취득일을 확인했나요?",
                "최근에 소유권이 바뀐 집은, 전세가율이 이미 높은 경우 보증금 반환 위험이 더 커질 수 있어요.",
                "얼마 전에 주인이 바뀐 집은 조금 더 조심해야 해요.\n\n"
                        + "만약 지금 주인이 대출을 많이 받아서 이 집을 산 거라면, 나중에 대출을 못 갚아서 집이 경매(강제로 팔리는 것)에 넘어갈 수 있어요. "
                        + "그러면 세입자는 **보증금을 못 돌려받을 수도 있어요**.\n\n"
                        + "그래서 언제 지금 주인이 이 집을 샀는지 확인해두면 좋아요.",
                ChecklistImportance.REQUIRED, ChecklistItemType.DATE, ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "임대인의 세금체납 여부를 확인해보셨나요?",
                "국세·지방세 완납증명서를 임대인에게 요청해 확인할 수 있어요 (자동 조회는 지원하지 않아요).",
                ChecklistImportance.GENERAL, ChecklistItemType.CHECK, ChecklistItemCode.TAX_DELINQUENCY_NOTICE, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "확정일자 부여현황을 임대인에게 요청했나요?",
                "선순위 보증금 규모를 확인하는 데 필요한 서류예요. 자동으로 조회되지 않아 직접 요청해야 해요.",
                "확정일자는 계약서에 '언제 이 집을 계약했는지' 도장 찍듯이 나라에서 증명해주는 거예요.\n\n"
                        + "이 집이 나중에 경매(강제로 팔리는 것)에 넘어가면, 확정일자를 먼저 받은 사람 순서대로 돈을 먼저 받아가요. "
                        + "'선순위 보증금'은 나보다 먼저 확정일자를 받아서 나보다 먼저 돈을 받아갈 사람들의 보증금을 다 합친 금액이에요.\n\n"
                        + "이게 너무 많으면, 집을 팔아도 내 보증금을 돌려줄 돈이 안 남을 수 있어요. "
                        + "그래서 계약 전에 임대인한테 **'확정일자 부여현황'이라는 서류를 달라고 해서**, 나보다 먼저 돈 받아갈 사람이 얼마나 있는지 미리 확인해야 해요.",
                ChecklistImportance.REQUIRED, ChecklistItemType.DOCUMENT_REQUEST, ChecklistItemCode.DATE_OF_CONFIRMATION_REQUEST, order);
        order = add(templates, ChecklistCategory.DOCUMENTS, "전입세대열람원을 임대인에게 요청했나요?",
                "다른 세입자의 전입 여부를 확인하는 서류예요. 자동으로 조회되지 않아 직접 요청해야 해요.",
                "전입세대열람원은 그 집에 누가 살고 있다고 나라에 신고했는지 보여주는 서류예요.\n\n"
                        + "계약서를 가지고 가까운 주민센터(행정복지센터)에 가면 발급받을 수 있고, 정부24 홈페이지에서도 가능해요.\n\n"
                        + "이 서류가 필요한 이유는, 나 말고 이미 그 집에 산다고 신고한 다른 사람이 있으면 **그 사람이 나보다 먼저 보증금을 받아갈 수도 있기 때문**이에요. "
                        + "그래서 계약 전에 미리 확인해두는 게 안전해요.",
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
        return add(templates, category, content, guideText, null, importance, itemType, code, displayOrder, null);
    }

    // MULTIPLE_CHOICE 타입 문항의 options까지 지정하는 오버로드.
    private static int addWithOptions(
            List<ChecklistItemTemplate> templates,
            ChecklistCategory category,
            String content,
            String guideText,
            ChecklistImportance importance,
            String options,
            int displayOrder
    ) {
        templates.add(ChecklistItemTemplate.builder()
                .version(VERSION)
                .category(category)
                .content(content)
                .guideText(guideText)
                .importance(importance)
                .itemType(ChecklistItemType.MULTIPLE_CHOICE)
                .options(options)
                .displayOrder(displayOrder)
                .active(true)
                .build());
        return displayOrder + 1;
    }

    // helperText(부동산 지식 없는 사용자를 위한 풀어쓴 설명)까지 지정하는 오버로드.
    private static int add(
            List<ChecklistItemTemplate> templates,
            ChecklistCategory category,
            String content,
            String guideText,
            String helperText,
            ChecklistImportance importance,
            ChecklistItemType itemType,
            ChecklistItemCode code,
            int displayOrder
    ) {
        return add(templates, category, content, guideText, helperText, importance, itemType, code, displayOrder, null);
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
        return add(templates, category, content, guideText, null, importance, itemType, code, displayOrder, applicablePropertyTypes);
    }

    private static int add(
            List<ChecklistItemTemplate> templates,
            ChecklistCategory category,
            String content,
            String guideText,
            String helperText,
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
                .helperText(helperText)
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
