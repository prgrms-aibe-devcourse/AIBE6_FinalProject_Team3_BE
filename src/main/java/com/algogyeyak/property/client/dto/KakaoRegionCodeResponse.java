package com.algogyeyak.property.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

/**
 * Kakao Local API - 좌표to행정구역 (GET https://dapi.kakao.com/v2/local/geo/coord2regioncode.json)
 * 응답 매핑용 DTO. region_type이 "B"(법정동)인 document의 code가 법정동코드 10자리다.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoRegionCodeResponse {

    @JsonProperty("documents")
    private List<Document> documents;

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Document {

        @JsonProperty("region_type")
        private String regionType; // "B"(법정동) 또는 "H"(행정동)

        @JsonProperty("code")
        private String code; // 10자리 코드

        @JsonProperty("address_name")
        private String addressName;
    }
}
