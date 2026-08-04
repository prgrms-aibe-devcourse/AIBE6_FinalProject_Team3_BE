package com.algogyeyak.property.entity;

/**
 * 서비스 타겟(사회초년생/대학생)에 맞춰 전월세만 지원한다. 매매(SALE)는 스코프 밖.
 */
public enum TransactionType {
    JEONSE,
    MONTHLY_RENT
}
