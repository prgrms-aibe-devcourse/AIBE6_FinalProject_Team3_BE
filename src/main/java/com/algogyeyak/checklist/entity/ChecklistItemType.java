package com.algogyeyak.checklist.entity;

/**
 * 문항이 사용자 입력을 받는 방식. ChecklistItem#answer(String)의 유효성 검증 분기 기준이 된다.
 * - CHECK: 단순 확인/미확인 (값 없음, check(boolean)로 처리)
 * - YES_NO: "Y" 또는 "N" 값 입력
 * - DATE: yyyy-MM-dd 형식의 날짜값 입력
 * - DOCUMENT_REQUEST: 임대인에게 요청한 서류의 제공 여부 ("PROVIDED" / "NOT_PROVIDED")
 * - MULTIPLE_CHOICE: 문항(템플릿)에 정의된 선택지(options) 중 하나를 입력 - 보일러 종류, 냉난방
 *   방식처럼 이진(양호/불량) 판정으로 담기 어려운 항목에 사용
 */
public enum ChecklistItemType {
    CHECK,
    YES_NO,
    DATE,
    DOCUMENT_REQUEST,
    MULTIPLE_CHOICE
}
