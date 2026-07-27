package com.algogyeyak.property.entity;

/**
 * 매물 신고 사유. ETC를 제외한 나머지는 고정 카테고리이며, ETC를 선택한 경우에만
 * {@link PropertyReport#getDetail()}에 상세 내용을 입력해야 한다(그 외 사유는 detail을 null로 강제).
 */
public enum PropertyReportReason {
    ALREADY_CONTRACTED,
    PRICE_MISMATCH,
    INFO_MISMATCH,
    DUPLICATE,
    ETC
}
