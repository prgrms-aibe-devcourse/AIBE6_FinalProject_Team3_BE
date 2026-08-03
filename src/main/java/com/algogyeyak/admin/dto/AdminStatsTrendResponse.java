package com.algogyeyak.admin.dto;

import java.time.LocalDate;
import java.util.List;

public record AdminStatsTrendResponse(
        List<Point> signups,
        List<Point> propertyRegistrations
) {
    public record Point(LocalDate date, long count) {
    }
}
