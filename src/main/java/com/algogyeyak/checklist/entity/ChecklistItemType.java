package com.algogyeyak.checklist.entity;

/**
 * 문항이 사용자 입력을 받는 방식. ChecklistItem#answer(String)의 유효성 검증 분기 기준이 된다.
 * - CHECK: 단순 확인/미확인 (값 없음, check(boolean)로 처리)
 * - YES_NO: "Y" 또는 "N" 값 입력
 * - DATE: yyyy-MM-dd 형식의 날짜값 입력
 * - DOCUMENT_REQUEST: 임대인에게 요청한 서류의 제공 여부 ("PROVIDED" / "NOT_PROVIDED")
 */
public enum ChecklistItemType {
    CHECK,
    YES_NO,
    DATE,
    DOCUMENT_REQUEST
}
