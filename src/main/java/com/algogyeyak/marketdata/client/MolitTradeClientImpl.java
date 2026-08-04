package com.algogyeyak.marketdata.client;

import com.algogyeyak.marketdata.client.dto.MolitTradeResponse;
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
 * 국토부 매매 실거래가 API 호출 구현체. {@link MolitRentClientImpl}과 요청 파라미터·인증·파싱
 * 방식이 전부 동일하고(같은 molit.service-key, 같은 User-Agent 우회 필요), URL과 응답의 금액
 * 필드(dealAmount 하나)만 다르다 - 두 클라이언트의 상세 주의사항은 MolitRentClientImpl 참고.
 *
 * 매매 API는 전월세 API와 달리 단독/다가구 jibun이 null이 아니라 "1**"처럼 뒷자리가 마스킹된 채로
 * 내려온다(2026-08-04 data.go.kr 미리보기로 확인) - null 체크만으로는 걸러지지 않으므로 toSample()에서
 * "*" 포함 여부까지 확인해서 지오코딩 대상에서 제외한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MolitTradeClientImpl implements MolitTradeClient {

    private static final String OFFICETEL_URL =
            "https://apis.data.go.kr/1613000/RTMSDataSvcOffiTrade/getRTMSDataSvcOffiTrade";
    private static final String MULTI_FAMILY_URL =
            "https://apis.data.go.kr/1613000/RTMSDataSvcRHTrade/getRTMSDataSvcRHTrade";
    private static final String DETACHED_HOUSE_URL =
            "https://apis.data.go.kr/1613000/RTMSDataSvcSHTrade/getRTMSDataSvcSHTrade";

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
    public List<TradeTransactionSample> fetch(PropertyType propertyType, String lawdCd, YearMonth dealYm) {
        String url = resolveUrl(propertyType);

        try {
            URI uri = UriComponentsBuilder.fromUriString(url)
                    .queryParam("serviceKey", molitServiceKey)
                    .queryParam("LAWD_CD", lawdCd)
                    .queryParam("DEAL_YMD", dealYm.format(DEAL_YMD_FORMAT))
                    .queryParam("numOfRows", 1000)
                    .queryParam("pageNo", 1)
                    .queryParam("_type", "json")
                    .build(true)
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, USER_AGENT);

            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class
            );
            String rawJson = response.getBody();
            MolitTradeResponse parsed = rawJson == null ? null : MOLIT_OBJECT_MAPPER.readValue(rawJson, MolitTradeResponse.class);

            if (parsed == null || parsed.getResponse() == null) {
                log.warn("국토부 매매 실거래가 응답이 예상 스키마가 아님 - propertyType: {}, lawdCd: {}, dealYm: {}, rawResponse: {}",
                        propertyType, lawdCd, dealYm, rawJson);
                return Collections.emptyList();
            }

            return toSamples(propertyType, parsed);

        } catch (RestClientException | IOException e) {
            log.error("국토부 매매 실거래가 API 호출 실패 - propertyType: {}, lawdCd: {}, dealYm: {}, error: {}",
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

    private List<TradeTransactionSample> toSamples(PropertyType propertyType, MolitTradeResponse body) {
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

    private TradeTransactionSample toSample(PropertyType propertyType, MolitTradeResponse.Item item) {
        try {
            LocalDate dealDate = LocalDate.of(
                    Integer.parseInt(item.getDealYear().trim()),
                    Integer.parseInt(item.getDealMonth().trim()),
                    Integer.parseInt(item.getDealDay().trim())
            );

            String areaRaw = propertyType == PropertyType.DETACHED_HOUSE ? item.getTotalFloorAr() : item.getExcluUseAr();

            return TradeTransactionSample.builder()
                    .propertyType(propertyType)
                    .buildingName(propertyType == PropertyType.OFFICETEL ? item.getOffiNm() : item.getMhouseNm())
                    .jibunAddress(resolveJibunAddress(item.getJibun()))
                    .legalDongCode(item.getSggCd())
                    .legalDongName(item.getUmdNm())
                    .dealDate(dealDate)
                    .dealAmountWon(parseManwonToWon(item.getDealAmount()))
                    .areaSqm(parseArea(areaRaw))
                    .build();
        } catch (RuntimeException e) {
            log.warn("국토부 매매 실거래가 item 파싱 실패 - propertyType: {}, error: {}", propertyType, e.getMessage());
            return null;
        }
    }

    /**
     * 단독/다가구는 jibun이 null이 아니라 "1**"처럼 마스킹된 채로 내려오므로, null/공백 체크만으로는
     * 걸러지지 않는다 - "*" 포함 여부까지 확인해서 지오코딩 대상에서 제외한다(마스킹된 지번으로는
     * 정확한 좌표를 구할 수 없기 때문).
     */
    private String resolveJibunAddress(String rawJibun) {
        if (rawJibun == null || rawJibun.isBlank() || rawJibun.contains("*")) {
            return null;
        }
        return rawJibun.trim();
    }

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
