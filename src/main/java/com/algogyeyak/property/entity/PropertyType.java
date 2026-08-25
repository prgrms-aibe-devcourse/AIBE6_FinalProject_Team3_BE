package com.algogyeyak.property.entity;

/**
 * 매물 유형. 직렬화 방식(JSON에서 enum name 그대로 노출, 예: "OFFICETEL")은 그대로 유지한다 -
 * FE가 이미 이 name을 값으로 쓰고 있어서(property-register.ts propertyTypeOptions), 별도
 * @JsonValue를 붙이면 기존 API 계약이 깨진다. displayName()은 이름(title) 미입력 시 대체값을
 * 만드는 용도로만 서버 내부에서 쓰는 순수 헬퍼다(#222).
 */
public enum PropertyType {
    OFFICETEL("오피스텔"),
    MULTI_FAMILY("연립다세대"),
    DETACHED_HOUSE("단독/다가구");

    private final String displayName;

    PropertyType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
