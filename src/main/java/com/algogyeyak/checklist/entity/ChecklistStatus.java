package com.algogyeyak.checklist.entity;

/**
 * 체크리스트 전체 진행 상태. 항목 상태가 바뀔 때마다 Checklist#refreshStatus()로 재계산한다.
 */
public enum ChecklistStatus {
    NOT_STARTED,  // 체크한 항목이 하나도 없음
    IN_PROGRESS,  // 일부만 체크됨
    COMPLETED     // 필수(REQUIRED) 항목을 모두 체크함
}
