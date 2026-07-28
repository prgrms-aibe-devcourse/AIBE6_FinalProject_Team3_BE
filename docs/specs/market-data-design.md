# 시세 데이터(market-data) 도메인 — 구현 현황 정리

## 배경 / 성격

`auth-design.md`, `user-design.md`, `property-design.md`와 같은 방식의 회고성(retroactive) 문서입니다.
이전 버전(이 문서의 초판)은 `market-data` 도메인이 전혀 구현되지 않은 시점의 스냅샷이었고,
이후 `feat/market-data-comparison` 브랜치에서 실거래가 비교 로직이 구현되어 `dev`에 반영됐습니다.
아래는 그 구현 완료 시점 기준으로 다시 작성한 내용입니다.

## 실제 구현 현황: 구현 완료 (반경 기반 실거래가 비교)

- `com.algogyeyak.marketdata` 패키지 신설: `client`(국토부 API 연동), `dto`(공용 응답), `service`(비교 로직),
  `util`(거리 계산), `config`(정책값)로 구성.
- `MolitRentClient`/`MolitRentClientImpl` — 국토부 전월세 실거래가 API 3종(오피스텔/연립다세대/단독다가구)을
  매물유형별로 호출하고 표준화된 `RentTransactionSample`로 정규화. `_type=json` 파라미터로 JSON 응답을 받고,
  공공데이터포털 특유의 "결과 1건일 때 배열이 아닌 단일 객체로 내려오는" 이슈에 대비해 별도 `ObjectMapper`
  (`ACCEPT_SINGLE_VALUE_AS_ARRAY`)로 파싱한다.
- `KakaoRegionCodeClient`/`KakaoRegionCodeClientImpl`(`property.client`) — Kakao 좌표to행정구역 API로
  매물 좌표에서 법정동코드(LAWD_CD)를 도출. 기존 `KakaoAddressClient`(주소 지오코딩)와 함께 사용된다.
- `MarketComparisonService` — 실제 비교 로직 오케스트레이션.
- `MarketComparisonProperties`(`@ConfigurationProperties`) — 반경/면적오차/표본기준/조회기간 정책값을
  `application.yml`(`market-data.comparison.*`)로 뺐다. M1 기간 실측 재검증 예정 값이라 하드코딩하지 않음.
- `property` 도메인의 `PropertyDetailResponse.MarketComparisonResponse` /
  `PropertyRegisterResponse.MarketComparisonResponse` 중복 정의를 `marketdata.dto.MarketComparisonResponse`
  공용 DTO로 교체. `PropertyService`가 `MarketComparisonService.compare(property)`를 호출해 실제 값을 채운다.

## 판정 로직 요약

1. **월세는 항상 UNAVAILABLE** — 전세가율/시세비교 개념 자체가 전세 대상.
2. **단독/다가구는 항상 UNAVAILABLE** — 국토부 API가 개인정보보호로 지번을 제공하지 않아 정확한 반경 계산이
   불가능. 근사치("동일 법정동")로 "인근"이라고 표시하는 것은 오히려 신뢰도를 해친다고 판단해 채택하지 않음.
   대신 "정확한 시세는 인근 공인중개사에 문의해보세요" 안내 문구를 반환.
3. 오피스텔/연립다세대(다세대)는 매물 좌표의 LAWD_CD로 최근 6개월치 실거래를 조회 → 전세 거래만 필터 →
   면적오차 ±20% 이내로 1차 필터링.
4. 남은 후보의 지번주소를 Kakao로 지오코딩(주소 문자열 단위 캐싱)해 실제 좌표를 구하고, 하버사인 거리로
   300m 이내 표본이 3건 이상이면 그걸 사용, 부족하면 600m로 확장.
5. 같은 법정동 내에서 600m 이내도 3건을 못 채우면 인접 법정동으로 넘어가지 않고 UNAVAILABLE("인근 실거래
   데이터가 부족해요") 처리 — v1 한계로, 부정확한 "인근" 비교보다 안전한 실패를 택함.
6. 지오코딩 자체가 실패하는 개별 후보(국토부 지번 표기가 카카오 검색과 안 맞는 경우 등)는 표본에서만
   제외되고 비교를 막지 않는다 — 실패 건은 debug/info 로그로 남겨 나중에 패턴을 확인할 수 있게 함.

## 요구사항 대비 대조

| 요구사항 항목 | 실제 상태 |
|---|---|
| 매매시세 계산 정책(반경/면적오차/표본기준/계산식/조회기간) | ✅ `market-data.comparison.*` 설정값으로 구현 |
| 국토부 API 호출(매물유형별 엔드포인트 분리) | ✅ 오피스텔/연립다세대/단독다가구 3종 분리 |
| 반경 확장(300m→600m) 로직 | ✅ 구현. 응답에 `radiusMeters` 필드로 실제 사용된 단계도 노출 |
| 중앙값 계산 | ✅ 구현 |
| 성공/판정불가/실패 3단계 응답 구조 | ⚠️ 사실상 AVAILABLE/UNAVAILABLE 2단계 — 외부 API 실패도 예외를 던지지 않고 UNAVAILABLE로 흡수(아래 비기능요구사항 참고) |
| 데이터 기준일·조회시각·적용 반경 단계 기록 | ⚠️ 기준일(`referenceDate`)·반경 단계(`radiusMeters`)는 응답에 포함. 조회시각 자체는 별도 기록 없음 |
| 응답 스키마(대표시세/차이/차이율/표본수/기준일/status) | ✅ `MarketComparisonResponse(status, referencePrice, differenceRate, sampleCount, referenceDate, radiusMeters, message)` |

## 비기능 요구사항 — 대조

| 항목 | 상태 |
|---|---|
| 실거래가 API 실패가 매물 등록/조회 전체 실패로 이어지지 않아야 함 | ✅ `MolitRentClientImpl`/`KakaoRegionCodeClientImpl`이 외부 API 예외를 내부에서 잡아 빈 결과/미해결로 처리하므로, `PropertyService`까지 예외가 전파되지 않는다 |
| 동일 매물 반복 조회 시 불필요한 외부 API 호출 감소(캐싱) | ⚠️ 지오코딩 결과는 `MarketComparisonService` 내부 메모리 캐시(요청 간 유지되는 싱글턴 빈 필드)로 재사용되지만, 매물 상세조회 자체는 요청마다 실시간으로 국토부/카카오 API를 다시 호출한다 — 트래픽이 늘면 매물 등록/수정 시점에만 계산해 저장하는 방식으로 전환 필요 |
| 주소/가격/거래유형 수정 시 기존 비교 결과 무효화 | N/A — 비교 결과를 저장하지 않고 조회 시점마다 실시간 재계산하므로 "무효화"할 저장된 상태 자체가 없음 |
| 실거래가 출처/기준일 사용자 안내 | ⚠️ 기준일(`referenceDate`)은 응답에 포함되나 "국토교통부 실거래가 공개시스템"같은 출처 문구는 FE에서 별도로 붙여야 함 |
| 반경 확장 여부 사용자 안내 | ✅ `radiusMeters` 필드로 300/600 중 실제 적용된 값을 노출 |
| 판정불가 사유 안내 | ✅ `message` 필드로 월세/단독다가구/좌표없음/지역코드조회실패/표본부족 등 사유별 문구 제공 |

## 남은 이슈 / 확인 필요 총정리

1. **FE 연동 미완료** — BE 응답 스키마와 로직은 준비됐지만, 매물 상세 화면에 `marketComparison` 결과를
   실제로 보여주는 UI(F3, 3주차 WBS 항목: "실거래가 비교 결과 UI", "서비스범위(전세+월세) vs
   전세가율적용범위(전세만) 안내 문구")는 아직 구현되지 않았다.
2. **실시간 재계산 vs 캐싱/저장** — 매물 상세조회마다 외부 API를 호출하는 구조라, 트래픽이 늘거나
   국토부 API 일일 호출 한도(계정당 1만 건)에 걸릴 가능성이 있다. 등록/수정 시점에만 계산해 결과를
   저장하는 방식으로 전환할지는 트래픽을 보고 판단하기로 함(현재는 MVP 범위에서 보류).
3. **전세가율/선순위보증금 검증은 별도 도메인(risk-analysis, B5 담당)** — `market-data`(이 도메인,
   B3 담당)의 "실거래가 비교"와 개념이 다르다(분모가 매매시세 vs 유사 전세 실거래 중앙값). WBS상
   4주차 B5 작업으로 별도 진행 예정이며, 두 기능이 국토부/카카오 API 연동 인프라를 상당 부분 공유하므로
   구현 시 조율이 필요하다.
4. **지오코딩 실패 패턴 모니터링** — 국토부 지번 표기(산번지 등)가 카카오 검색과 맞지 않아 개별 후보가
   지오코딩에 실패하는 경우 로그로만 남기고 있다. 실사용 로그를 보고 패턴이 확인되면 정규화 로직을
   추가하는 방향으로 갈 예정 (근거 없는 근사 폴백은 채택하지 않기로 함).
