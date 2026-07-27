package com.algogyeyak.contractanalysis.client.dto;

import java.util.List;

public record ClovaOcrRequestMessage(
        String version,
        String requestId,
        long timestamp,
        String lang,
        List<Image> images
) {
    public record Image(
            String format,
            String name
    ) {
    }
}
