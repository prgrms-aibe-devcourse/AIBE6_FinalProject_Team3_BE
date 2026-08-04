package com.algogyeyak.marketdata.client;

import com.algogyeyak.marketdata.client.dto.MolitRentResponse;
import com.algogyeyak.property.entity.PropertyType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * 국토부 전월세 실거래가 API 호출 구현체.
 * 매물유형별 API 상품(오피스텔/연립다세대/단독다가구)의 base URL만 다르고 나머지 요청/응답 구조는 동일하다.
 *
 * 주의: molit.service-key 값은 공공데이터포털에서 발급받은 "일반 인증키(Decoding)"를 사용할 것.
 * "Encoding" 키를 그대로 넣으면 UriComponentsBuilder가 다시 인코딩해 이중 인코딩으로 인증에 실패한다.
 *
 * 주의 2: 요청에 User-Agent 헤더를 반드시 붙여야 한다 - data.go.kr 게이트웨이가 curl/Java의 기본
 * User-Agent(예: "Java/21.0.x")가 붙은 요청을 차단하는 것으로 확인됨(2026-08-04). 이 경우 HTTP
 * 자체는 200 OK로 응답하되 바디에 {"cmmMsgHeader":{"errMsg":"HTTP_ERROR", ...}} 형태의 에러가
 * 담겨오기 때문에 RestClientException으로 잡히지 않고, toSamples()가 이를 "그냥 데이터 없음"과
 * 구분하지 못해 조용히 빈 리스트를 반환해버린다 - 그 결과 시세비교가 항상 UNAVAILABLE로 나오는데도
 * 로그에는 아무 단서도 안 남는 문제가 있었다. brew/curl로 재현 및 원인 확인함.
 *
 * RestTemplate의 기본 Jackson 컨버터를 그대로 쓰지 않고 문자열로 받아 별도 ObjectMapper로 파싱하는 이유:
 * 공공데이터포털 XML->JSON 변환은 결과가 1건일 때 item을 배열이 아닌 단일 객체로 내려주는 경우가 있어
 * (ACCEPT_SINGLE_VALUE_AS_ARRAY 필요) 앱 전역 ObjectMapper 설정을 건드리지 않고 이 클라이언트에서만 대응한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MolitRentClientImpl implements MolitRentClient {

    private static final String OFFICETEL_URL =
            "https://apis.data.go.kr/1613000/RTMSDataSvcOffiRent/getRTMSDataSvcOffiRent";
    private static final String MULTI_FAMILY_URL =
            "https://apis.data.go.kr/1613000/RTMSDataSvcRHRent/getRTMSDataSvcRHRent";
    private static final String DETACHED_HOUSE_URL =
            "https://apis.data.go.kr/1613000/RTMSDataSvcSHRent/getRTMSDataSvcSHRent";

    // data.go.kr 게이트웨이가 curl/Java 기본 User-Agent를 차단해서 임의로 브라우저처럼 보이는 값을 붙인다.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final DateTimeFormatter DEAL_YMD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    private static final ObjectMapper MOLIT_OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestTemplate restTemplate;

    @Value("${molit.service-key}")
    private String molitServiceKey;

    @Override
    public List<RentTransactionSample> fetch(PropertyType propertyType, String lawdCd, YearMonth dealYm) {
        String url = resolveUrl(propertyType);

        try {
            URI uri = UriComponentsBuilder.fromUriString(url)
                    .queryParam("serviceKey", molitServiceKey)
                    .queryParam("LAWD_CD", lawdCd)
                    .queryParam("DEAL_YMD", dealYm.format(DEAL_YMD_FORMAT))
                    .queryParam("numOfRows", 1000)
                    .queryParam("pageNo", 1)
                    .queryParam("_type", "json")
                    .build(true) // serviceKey가 이미 인코딩된 값일 수 있어 재인코딩하지 않는다
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, USER_AGENT);

            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class
            );
            String rawJson = response.getBody();
            MolitRentResponse parsed = rawJson == null ? null : MOLIT_OBJECT_MAPPER.readValue(rawJson, MolitRentResponse.class);

            if (parsed == null || parsed.getResponse() == null) {
                // 정상 스키마({"response":{"header":...,"body":...}})가 아니라는 뜻 - 국토부 API 자체
                // 에러 응답({"cmmMsgHeader":{"errMsg":...}})일 가능성이 높다. 진짜 "데이터 없음"과
                // 구분하기 위해 원본을 남긴다(개인정보 없는 공개 실거래 데이터라 로그에 남겨도 무방).
                log.warn("국토부 실거래가 응답이 예상 스키마가 아님 - propertyType: {}, lawdCd: {}, dealYm: {}, rawResponse: {}",
                        propertyType, lawdCd, dealYm, rawJson);
                return Collections.emptyList();
            }

            return toSamples(propertyType, parsed);

        } catch (RestClientException | IOException e) {
            log.error("국토부 실거래가 API 호출 실패 - propertyType: {}, lawdCd: {}, dealYm: {}, error: {}",
                    propertyType, lawdCd, dealYm, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String resolveUrl(PropertyType propertyType) {
        return switch (propertyType) {
            case OFFICETEL -> OFFICETEL_URL;
            case MULTI_FAMILY -> MULTI_FAMILY_URL;
            case DETACHED_HOUSE -> DETACHED_HOUSE_URL;
        };
    }

    private List<RentTransactionSample> toSamples(PropertyType propertyType, MolitRentResponse body) {
        if (body == null || body.getResponse() == null
                || body.getResponse().getBody() == null
                || body.getResponse().getBody().getItems() == null
                || body.getResponse().getBody().getItems().getItem() == null) {
            return Collections.emptyList();
        }

        return body.getResponse().getBody().getItems().getItem().stream()
                .map(item -> toSample(propertyType, item))
                .filter(sample -> sample != null)
                .toList();
    }

    private RentTransactionSample toSample(PropertyType propertyType, MolitRentResponse.Item item) {
        try {
            LocalDate dealDate = LocalDate.of(
                    Integer.parseInt(item.getDealYear().trim()),
                    Integer.parseInt(item.getDealMonth().trim()),
                    Integer.parseInt(item.getDealDay().trim())
            );

            String areaRaw = propertyType == PropertyType.DETACHED_HOUSE ? item.getTotalFloorAr() : item.getExcluUseAr();

            return RentTransactionSample.builder()
                    .propertyType(propertyType)
                    .buildingName(propertyType == PropertyType.OFFICETEL ? item.getOffiNm() : item.getMhouseNm())
                    .jibunAddress(item.getJibun() == null || item.getJibun().isBlank() ? null : item.getJibun().trim())
                    .legalDongCode(item.getSggCd())
                    .legalDongName(item.getUmdNm())
                    .dealDate(dealDate)
                    .depositWon(parseManwonToWon(item.getDeposit()))
                    .monthlyRentWon(parseManwonToWon(item.getMonthlyRent()))
                    .areaSqm(parseArea(areaRaw))
                    .build();
        } catch (RuntimeException e) {
            log.warn("국토부 실거래가 item 파싱 실패 - propertyType: {}, error: {}", propertyType, e.getMessage());
            return null;
        }
    }

    /**
     * 국토부 응답의 보증금/월세는 "만원" 단위 + 쉼표 포함 문자열(예: "24,000")로 내려온다.
     * Property 엔티티의 deposit/monthlyRent는 "원" 단위이므로 변환해준다.
     */
    private long parseManwonToWon(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String cleaned = raw.replace(",", "").trim();
        if (cleaned.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(cleaned) * 10_000L;
    }

    private Double parseArea(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Double.parseDouble(raw.trim());
    }
}
