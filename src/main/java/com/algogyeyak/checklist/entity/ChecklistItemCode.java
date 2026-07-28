package com.algogyeyak.checklist.entity;

/**
 * 자동 issueFound 규칙이나 다른 도메인(risk-analysis 등)과의 연계가 필요한 특정 문항을 식별하기 위한 코드.
 * 일반 문항(대부분의 실내/소음/보안/주변 항목)은 code가 없다(null).
 */
public enum ChecklistItemCode {
    TRUST_REGISTRATION,          // 신탁등기 여부 (Y면 자동 issueFound)
    OWNERSHIP_MATCH,             // 소유자-임대인 명의가 다른지 여부 (Y, 즉 불일치면 자동 issueFound)
    OWNERSHIP_ACQUISITION_DATE,  // 소유권 취득일 (risk-analysis 전세가율과 연계되는 보조 신호용, 자동 issueFound 규칙 없음)
    TAX_DELINQUENCY_NOTICE,      // 세금체납 여부 확인 안내 (자동조회 아닌 안내 문구, 자동 issueFound 규칙 없음)
    DATE_OF_CONFIRMATION_REQUEST,  // 확정일자 부여현황 요청 (서류 미제공 시 자동 issueFound)
    RESIDENT_REGISTRATION_REQUEST  // 전입세대열람원 요청 (서류 미제공 시 자동 issueFound)
}
