package com.algogyeyak.checklist.entity;

/**
 * 필수 확인 항목(REQUIRED)과 일반 항목(GENERAL)을 구분한다.
 * 결과 계산 시 "필수 확인 누락 수"는 REQUIRED 항목만 대상으로 한다.
 */
public enum ChecklistImportance {
    REQUIRED,
    GENERAL
}
