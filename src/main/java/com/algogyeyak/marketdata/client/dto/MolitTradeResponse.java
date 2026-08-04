package com.algogyeyak.marketdata.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

/**
 * 국토부 매매 실거래가 API(오피스텔/연립다세대/단독다가구 공통) 응답 DTO.
 * {@link MolitRentResponse}와 동일한 이유로 세 API를 하나로 통합해서 매핑한다(ignoreUnknown).
 * 전월세 API와 요청 파라미터(LAWD_CD, DEAL_YMD, serviceKey, User-Agent 헤더 필요 등)는 동일하고,
 * item 안의 금액 필드만 deposit/monthlyRent 대신 dealAmount 하나다.
 *
 * 2026-08-04 data.go.kr 미리보기로 실제 응답 필드 확인함(오피스텔/연립다세대/단독다가구 3종 모두):
 * - 오피스텔: offiNm(건물명), excluUseAr(전용면적)
 * - 연립다세대: mhouseNm(건물명), houseType(연립/다세대), excluUseAr(전용면적)
 * - 단독/다가구: houseType(단독/다가구), totalFloorAr(연면적), 건물명 필드 없음
 * 단독/다가구는 jibun이 완전히 비어있지 않고 "1**"처럼 뒷자리가 마스킹돼서 내려온다 - null 체크만으로는
 * 안 걸러지므로, 지오코딩 대상에서 제외할 땐 마스킹 패턴("*" 포함 여부)까지 확인해야 한다.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class MolitTradeResponse {

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
        // 받은 뒤 서비스단에서 안전하게 처리한다. (MolitRentResponse.Items와 동일한 이유)
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

        @JsonProperty("dealAmount")
        private String dealAmount; // 만원 단위, 쉼표 포함 문자열 (예: "24,680") - deposit/monthlyRent 대신 이 필드 하나만 있음

        @JsonProperty("excluUseAr")
        private String excluUseAr; // 전용면적 (오피스텔/연립다세대)

        @JsonProperty("totalFloorAr")
        private String totalFloorAr; // 연면적 (단독/다가구 전용 필드)

        @JsonProperty("houseType")
        private String houseType; // 연립/다세대, 단독/다가구 (오피스텔엔 없음)

        @JsonProperty("jibun")
        private String jibun; // 오피스텔/연립다세대는 전체 제공, 단독/다가구는 "1**"처럼 뒷자리 마스킹돼서 옴(완전 null 아님)

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
