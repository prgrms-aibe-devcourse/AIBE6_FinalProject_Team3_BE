package com.algogyeyak.user.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum CurrentStage {
    FIRST_TIME("자취 처음"),
    EXPERIENCED("자취 경험 있음");

    private final String label;

    CurrentStage(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    @JsonCreator
    public static CurrentStage from(String label) {
        return Arrays.stream(values())
                .filter(stage -> stage.label.equals(label))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 현재 단계입니다: " + label));
    }
}
