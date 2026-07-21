package com.algogyeyak.property.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

/**
 * Kakao Local API - 주소 검색 (GET https://dapi.kakao.com/v2/local/search/address.json)
 * 응답 매핑용 DTO. Kakao 응답은 snake_case라 @JsonProperty로 매핑함.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoAddressSearchResponse {

    @JsonProperty("meta")
    private Meta meta;

    @JsonProperty("documents")
    private List<Document> documents;

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        @JsonProperty("total_count")
        private int totalCount;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Document {

        @JsonProperty("address_name")
        private String addressName; // 지번 주소

        @JsonProperty("x")
        private String longitude; // 경도 (문자열로 내려옴)

        @JsonProperty("y")
        private String latitude;  // 위도 (문자열로 내려옴)

        @JsonProperty("road_address")
        private RoadAddress roadAddress; // 도로명 주소 정보 없으면 null
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoadAddress {

        @JsonProperty("address_name")
        private String addressName; // 도로명 주소
    }
}
