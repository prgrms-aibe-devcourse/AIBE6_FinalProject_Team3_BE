package com.algogyeyak.marketdata.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

/**
 * 국토부 전월세 실거래가 API(오피스텔/연립다세대/단독다가구 공통) 응답 DTO.
 * 세 API가 구조는 동일하고 item 필드만 일부 다르므로(ignoreUnknown) 하나로 통합해서 매핑한다.
 * 요청 시 "_type=json" 파라미터를 붙여 XML 대신 JSON으로 응답받는다(공공데이터포털 공통 관례).
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class MolitRentResponse {

    @JsonProperty("response")
    private Response response;

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {
        @JsonProperty("header")
        private Header header;

        @JsonProperty("body")
        private Body body;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Header {
        @JsonProperty("resultCode")
        private String resultCode;

        @JsonProperty("resultMsg")
        private String resultMsg;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Body {
        @JsonProperty("items")
        private Items items;

        @JsonProperty("totalCount")
        private Integer totalCount;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Items {
        // totalCount==0 이면 item 자체가 빈 문자열("")로 내려오는 경우가 있어 List가 아닌 Object로
        // 받은 뒤 서비스단에서 안전하게 처리한다.
        @JsonProperty("item")
        private List<Item> item;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        @JsonProperty("dealYear")
        private String dealYear;

        @JsonProperty("dealMonth")
        private String dealMonth;

        @JsonProperty("dealDay")
        private String dealDay;

        @JsonProperty("deposit")
        private String deposit; // 만원 단위, 쉼표 포함 문자열 (예: "24,000")

        @JsonProperty("monthlyRent")
        private String monthlyRent; // 만원 단위

        @JsonProperty("excluUseAr")
        private String excluUseAr; // 전용면적 (오피스텔/연립다세대)

        @JsonProperty("totalFloorAr")
        private String totalFloorAr; // 연면적 (단독/다가구 전용 필드)

        @JsonProperty("houseType")
        private String houseType; // 연립/다세대, 단독/다가구 (오피스텔엔 없음)

        @JsonProperty("jibun")
        private String jibun; // 오피스텔/연립다세대만 제공, 단독/다가구는 개인정보보호로 미제공

        @JsonProperty("mhouseNm")
        private String mhouseNm; // 연립다세대 건물명

        @JsonProperty("offiNm")
        private String offiNm; // 오피스텔명

        @JsonProperty("sggCd")
        private String sggCd;

        @JsonProperty("umdNm")
        private String umdNm;
    }
}
