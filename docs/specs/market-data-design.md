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
| 매매시세 계산 정책(반경/면적오차/표본기준/계산식/조회기간) | ✅ `market-data.comparison.*` 설정값으로 구현. 면적오차(`areaErrorRate`)·조회기간(`lookbackMonths`)은 응답에도 그대로 노출해(#206) FE가 "왜 이 표본으로 비교됐는지" 근거를 보여줄 수 있다 |
| 국토부 API 호출(매물유형별 엔드포인트 분리) | ✅ 오피스텔/연립다세대/단독다가구 3종 분리 |
| 반경 확장(300m→600m) 로직 | ✅ 구현. 응답에 `radiusMeters` 필드로 실제 사용된 단계도 노출 |
| 중앙값 계산 | ✅ 구현 |
| 성공/판정불가/실패 3단계 응답 구조 | ⚠️ 사실상 AVAILABLE/UNAVAILABLE 2단계 — 외부 API 실패도 예외를 던지지 않고 UNAVAILABLE로 흡수(아래 비기능요구사항 참고) |
| 데이터 기준일·조회시각·적용 반경 단계 기록 | ⚠️ 기준일(`referenceDate`)·반경 단계(`radiusMeters`)는 응답에 포함. 조회시각 자체는 별도 기록 없음 |
| 응답 스키마(대표시세/차이/차이율/표본수/기준일/status) | ✅ `MarketComparisonResponse(status, referencePrice, differenceRate, sampleCount, referenceDate, radiusMeters, areaErrorRate, lookbackMonths, samples, message, reason)`. `areaErrorRate`/`lookbackMonths`는 AVAILABLE일 때만 채워지고 UNAVAILABLE이면 `radiusMeters`와 동일하게 null(#206) |
| 기준가 산출 근거(개별 실거래 표본) 노출 | ✅ **(2026-08-21 추가/정정, 5차 멘토링 피드백 7-2)** `samples: List<MarketTransactionSampleResponse>` 추가 — 중앙값 계산에 실제로 쓰인 표본(반경·면적오차·전세 필터를 모두 통과한 것)을 건물명/조합주소/계약일/보증금/면적과 함께 담는다. 처음엔 쓰인 표본을 전부 노출했었는데, 대단지 등에서 표본이 많으면 응답이 무거워지고 사용자도 다 읽기 부담스럽다는 피드백을 받아 `MarketComparisonService.MAX_EXPOSED_SAMPLES`(5)건으로 제한 — 최고가 1건 + 최저가 1건("이 가격대가 왜 이렇게 나왔는지" 감을 잡게) + 나머지는 최근 계약일순으로 채우고, 최종 목록은 항상 최신 계약일 순으로 정렬해 반환한다. 표본이 5건 이하면 그대로 전부 반환. 총 표본 수는 `sampleCount`가 그대로 담당하므로(제한 없음) `samples.size()`가 `sampleCount`보다 작으면 일부만 노출됐다는 뜻. 국토부 실거래가 공개시스템이 원래도 공개하는 공공데이터라 별도 개인정보 이슈는 없음. UNAVAILABLE이면 null |

## 비기능 요구사항 — 대조

| 항목 | 상태 |
|---|---|
| 실거래가 API 실패가 매물 등록/조회 전체 실패로 이어지지 않아야 함 | ✅ `MolitRentClientImpl`/`KakaoRegionCodeClientImpl`이 외부 API 예외를 내부에서 잡아 빈 결과/미해결로 처리하므로, `PropertyService`까지 예외가 전파되지 않는다 |
| 동일 매물 반복 조회 시 불필요한 외부 API 호출 감소(캐싱) | ✅ `MarketComparisonService.compare(Property)`가 propertyId 기준으로 Redis에 캐싱됨(`@Cacheable`, TTL `market-data.comparison.cache-ttl-minutes` 기본 30분) — 같은 매물을 반복 조회해도 캐시가 살아있는 동안은 국토부/카카오 API를 다시 호출하지 않는다. 지오코딩 결과는 여전히 `MarketComparisonService` 내부 메모리 캐시(싱글턴 빈 필드, Redis 아님)로 별도 재사용됨 |
| 주소/가격/거래유형 수정 시 기존 비교 결과 무효화 | ✅ `PropertyService.update()`가 가격/면적 변경 후 `MarketComparisonService.evictCache(propertyId)`로 캐시를 명시적으로 비우고 재계산한다(안 그러면 캐시 히트로 수정 전 결과가 그대로 반환됨). 주소/거래유형은 애초에 수정 불가능한 필드라 해당 경로 자체가 없음 |
| 실거래가 출처/기준일 사용자 안내 | ⚠️ 기준일(`referenceDate`)은 응답에 포함되나 "국토교통부 실거래가 공개시스템"같은 출처 문구는 FE에서 별도로 붙여야 함 |
| 반경 확장 여부 사용자 안내 | ✅ `radiusMeters` 필드로 300/600 중 실제 적용된 값을 노출 |
| 판정불가 사유 안내 | ✅ `message` 필드로 월세/단독다가구/좌표없음/지역코드조회실패/표본부족 등 사유별 문구 제공 |

## 버그 수정 이력

- **국토부 API가 User-Agent 없는 요청을 차단 (2026-08-04 발견/수정)**: `MolitRentClientImpl`이
  curl/Java 기본 User-Agent로 호출하면 data.go.kr 게이트웨이가 이를 차단, HTTP 200 + 에러 바디
  (`{"cmmMsgHeader":{"errMsg":"HTTP_ERROR",...}}`)로 응답하는데, 이 형태는 예외가 아니라서
  `MolitRentClientImpl`이 이를 "그냥 실거래 데이터 없음"과 구분하지 못하고 조용히 빈 리스트를
  반환했다 - 그 결과 실제로는 API 호출이 매번 차단당하고 있었는데도 시세비교 결과는 그럴듯하게
  "인근 실거래 데이터가 부족해요"로만 나와서 원인 파악이 어려웠다. curl로 브라우저 User-Agent를
  붙여 호출하니 정상 데이터(345건)가 내려오는 것으로 원인 확인, 요청에 브라우저 User-Agent 헤더를
  명시적으로 붙이도록 수정했고, 향후 같은 문제 재발 시 로그로 확인 가능하도록 `response` 필드가
  예상 스키마가 아닐 때(에러 응답으로 추정될 때) `log.warn`으로 원본 응답을 남기도록 했다.

## 남은 이슈 / 확인 필요 총정리

1. ~~**FE 연동 미완료** — BE 응답 스키마와 로직은 준비됐지만, 매물 상세 화면에 `marketComparison` 결과를
   실제로 보여주는 UI(F3, 3주차 WBS 항목: "실거래가 비교 결과 UI", "서비스범위(전세+월세) vs
   전세가율적용범위(전세만) 안내 문구")는 아직 구현되지 않았다.~~ **(2026-08-12 정정)** FE 연동 완료됨 —
   `frontend/app/(main)/properties/[id]/PropertyDetailClient.tsx`가 `marketComparison`을 반경/표본수/차이율/
   기준일/판정불가 사유까지 전부 렌더링하고 있고, `frontend/docs/specs/market-data-design.md`도 이미 "FE 작업
   완료" 시점으로 갱신되어 있다(전수조사 결과 코드 품질 6번 참고).
2. ~~실시간 재계산 vs 캐싱/저장~~ → **조회 결과를 Redis에 TTL(기본 30분) 동안 캐싱하는 방식으로
   부분 해소됨** (`MarketComparisonService.compare()` `@Cacheable`, propertyId 기준). 매물
   상세조회를 반복해도 캐시가 살아있는 동안은 외부 API를 다시 호출하지 않는다. 단, 등록/수정
   시점에만 계산해서 영구 저장하는 더 큰 구조 전환(캐시가 아니라 저장)은 여전히 하지 않았다 —
   트래픽을 보고 판단하기로 한 결정은 유지됨. 캐시 TTL 동안은 매물 소유자 본인이 봐도 시세비교
   결과가 갱신되지 않을 수 있다는 트레이드오프가 있음(수정 시에는 즉시 갱신됨).
3. **전세가율/선순위보증금 검증은 별도 도메인(risk-analysis, B5 담당)** — `market-data`(이 도메인,
   B3 담당)의 "실거래가 비교"와 개념이 다르다(분모가 매매시세 vs 유사 전세 실거래 중앙값). WBS상
   4주차 B5 작업으로 별도 진행 예정이며, 두 기능이 국토부/카카오 API 연동 인프라를 상당 부분 공유하므로
   구현 시 조율이 필요하다.
4. **지오코딩 실패 패턴 모니터링** — 국토부 지번 표기(산번지 등)가 카카오 검색과 맞지 않아 개별 후보가
   지오코딩에 실패하는 경우 로그로만 남기고 있다. 실사용 로그를 보고 패턴이 확인되면 정규화 로직을
   추가하는 방향으로 갈 예정 (근거 없는 근사 폴백은 채택하지 않기로 함).

## 전수조사 결과 (2026-08-12)

### 버그/정확성

1. **`referencePrice`가 0일 때 `differenceRate`가 Infinity/NaN이 될 수 있음** —
   `MarketComparisonService.compare()`(라인 121-122)의 `differenceRate = (property.getDeposit() - referencePrice) / (double) referencePrice`는
   `referencePrice`(표본 보증금의 median)가 0이 되는 경우를 방어하지 않는다. `MolitRentClientImpl.parseManwonToWon()`(라인 168-177)은
   `deposit` 필드가 null/blank면 조용히 `0L`을 반환하므로, 국토부 원본 데이터에 보증금 필드가 비어있는 이상 레코드가
   표본의 절반 이상을 차지하면(실무에선 드물지만 공공데이터 특유의 결측치 가능성은 있음) median이 0이 되어
   `differenceRate`가 `Infinity`/`NaN`으로 계산된다. Jackson 기본 설정에서 `NaN`/`Infinity`는 유효하지 않은 JSON 리터럴로
   직렬화되어 응답 자체가 깨질 수 있다. 표본에서 `depositWon <= 0`인 건을 사전에 걸러내거나, `referencePrice == 0`일 때
   `INSUFFICIENT_SAMPLE`(또는 별도 사유)로 UNAVAILABLE 처리하는 방어 로직 추가를 권장한다. 동일 패턴이
   `MolitTradeClientImpl.parseManwonToWon()`을 쓰는 매매 조회 경로엔 없음(매매는 `differenceRate` 계산이 없어 해당 없음).

2. **좌표 범위 검증 없음** — `GeoDistanceCalculator.distanceInMeters()`와 이를 호출하는
   `MarketComparisonService.filterByRadius()`/`MarketSaleComparisonService.filterByRadius()`는 위경도 값이 유효 범위
   (-90~90, -180~180)인지 확인하지 않는다. `property.getAddress()`의 좌표는 이미 Kakao 지오코딩을 거친 값이라 실무상
   발생 확률은 낮지만, market-data 도메인 자체엔 방어 코드가 전혀 없다는 점은 사실이다 — 이상값이 들어와도 예외는
   나지 않고(삼각함수 계산은 항상 값을 반환) 단지 거리 판정이 무의미해질 뿐이라 크래시 위험은 없음.

### 보안

1. market-data 패키지엔 자체 `@RestController`가 없고 `MarketComparisonService`/`MarketSaleComparisonService` 모두
   `PropertyService`/`MarketDataClientImpl`(risk-analysis) 등 내부 서비스 계층을 통해서만 호출된다 — 사용자가 반경/좌표
   값을 직접 넘길 수 있는 엔드포인트 자체가 없으므로, 이 도메인 범위에서 입력값 조작에 의한 별도 인가 우회 경로는
   확인되지 않았다.
2. 국토부 API 원본 응답 필드(지번주소/건물명 등)는 DB에 영속화되지 않고 지오코딩 조합주소 생성과 로그 출력에만
   쓰인다 — 저장형 인젝션(stored XSS 등) 경로는 확인되지 않았다. 다만 `MolitRentClientImpl`/`MolitTradeClientImpl`이
   `log.warn`으로 원본 응답 전체(`rawJson`)를 그대로 남기는 부분(각각 101-102줄, 88-89줄)은 국토부 API가 향후 개인정보를
   포함한 필드를 추가하더라도 그대로 로그에 찍힐 수 있다는 점은 유의할 부분(현재는 공개 실거래 데이터라 문제 없음).

### 코드 품질 (중복/구조/일관성)

1. **`MolitRentClientImpl`과 `MolitTradeClientImpl`가 거의 완전히 중복** — `USER_AGENT` 상수, `MOLIT_OBJECT_MAPPER` 설정,
   `fetch()`의 요청 구성/헤더/예외처리 흐름, `parseManwonToWon()`, `parseArea()`가 두 클래스에 바이트 단위로 동일하다.
   클래스 주석(`MolitTradeClientImpl` 27-30줄)도 "요청 파라미터·인증·파싱 방식이 전부 동일"하다고 스스로 밝히고 있다 —
   제네릭 응답 타입을 받는 공통 추상 클래스나 템플릿 메서드로 추출하면 중복을 없앨 수 있다.
2. **`MarketComparisonService`와 `MarketSaleComparisonService`의 알고리즘 중복** — `median()`, `extractSggPrefix()`,
   `filterByRadius()`, `isWithinAreaTolerance()`, `geocodeAll()`이 제네릭 타입(RentTransactionSample vs
   TradeTransactionSample)만 다를 뿐 로직이 동일하다. 클래스 주석은 "의도적으로 별도 클래스로 둔다"고 설명하지만,
   그 근거(서로 다른 진화 속도, 독립성)는 두 서비스가 별도 클래스인 이유는 되어도 알고리즘 자체를 복붙해야 하는 이유는
   아니다 — 공통 로직만 별도 유틸/제네릭 베이스 클래스로 뽑고 두 서비스는 각자의 판정 분기(월세/단독다가구 처리,
   differenceRate 유무)만 갖는 구조로 정리할 여지가 있다.
3. **`MarketSaleComparisonService.compare()`엔 캐싱이 없음** — `MarketComparisonService.compare()`는
   `@Cacheable(cacheNames = "marketComparison", ...)`로 propertyId 기준 Redis 캐싱이 적용돼 있지만(비기능요구사항 표의
   "동일 매물 반복 조회 시 캐싱" ✅ 항목), 이후 risk-analysis용으로 추가된 `MarketSaleComparisonService.compare()`에는
   동일한 애노테이션이 없다. `MarketSaleDataClientImpl.getSalePrice()`(risk-analysis)가 이를 호출할 때마다 캐시 없이
   매번 국토부/카카오 API를 다시 호출한다 — 같은 매물에 대해 전세 비교와 매매 비교가 캐싱 정책상 일관되지 않은 상태다.
4. **인메모리 지오코딩 캐시가 무제한 누적됨** — `MarketComparisonService.geocodeCache`와
   `MarketSaleComparisonService.geocodeCache`는 각각 독립된 `ConcurrentHashMap`으로, TTL도 크기 제한도 없어 프로세스가
   떠 있는 동안 계속 커진다(Redis 쪽 최종 결과 캐시는 TTL이 명시적으로 있는 것과 대조적). 장기 운영 시 힙 사용량을
   모니터링하거나 상한/만료 정책(예: Caffeine으로 교체)을 고려할 필요가 있다.
5. **문서가 이미 구현된 `MarketSaleComparisonService` 관련 코드를 전혀 다루지 않음** — 이 문서("실제 구현 현황"
   섹션)는 `MarketComparisonService`(전세 시세비교)만 설명하고, 같은 패키지에 존재하며 risk-analysis가 이미 소비 중인
   `MarketSaleComparisonService`/`MarketSaleComparisonResponse`/`MolitTradeClient`/`MolitTradeClientImpl`/
   `TradeTransactionSample`은 언급이 없다. "남은 이슈" 3번이 "WBS 4주차 B5 작업으로 별도 진행 예정"이라고 미래형으로
   서술하고 있지만, 실제로는 이미 구현되어 `riskanalysis.client.MarketSaleDataClientImpl`이 사용 중이다 — 문서 갱신 필요.
6. **"남은 이슈 1. FE 연동 미완료"는 이제 사실과 다름** — 실제로 `frontend/app/mappers/property.ts`의
   `mapMarketComparisonDto`/`mapPropertyDetailResponseDto`와 `frontend/app/(main)/properties/[id]/PropertyDetailClient.tsx`
   (약 239-267줄)가 매물 상세화면에서 `marketComparison`을 반경/표본수/차이율/기준일/판정불가 사유까지 전부 렌더링하고
   있으며, `frontend/docs/specs/market-data-design.md` 자체도 이미 "FE 작업 완료" 시점으로 갱신되어 있다. 이 문서만
   구버전 서술(남은 이슈 1번)이 남아있는 상태 — 삭제 또는 갱신이 필요하다(자세한 내용은 frontend 쪽 문서 참고).
7. ~~**목록조회(`GET /properties`)가 페이지의 매물마다 순차적으로 `marketComparisonService.compare()`를 호출함** —
   `PropertyService`의 목록조회 스트림(195번째 줄 부근)이 각 매물에 대해 `compare()`를 호출한다. 캐시가 살아있는
   매물은 Redis 히트로 끝나지만, 캐시 미스가 몰린 상황(배포 직후 Redis 초기화, TTL 만료 등)에서는 목록조회 한 번이
   페이지 크기만큼의 국토부/카카오 API 호출을 순차적으로 트리거할 수 있다. 코드 주석이 이 트레이드오프를 이미
   인지하고 있으나("심각하게 느려지진 않는다"), 실측 근거는 없는 가정이라 트래픽이 늘면 재검토가 필요하다.~~
   ✅ **(2026-08-21 해결)** 홈 화면이 `size=100`까지 요청하면서 실측으로 문제가 확인됨(캐시 미스 매물마다 Kakao
   지역코드 조회 1회 + 최근 `lookbackMonths`(기본 6)개월치 MOLIT 순차 호출 + 후보 지번주소마다 Kakao 지오코딩까지
   전부 목록 크기만큼 증폭). `MarketComparisonService`에 `getCachedOnly(propertyId)`를 추가해 `@Cacheable` 프록시를
   거치지 않고 `CacheManager`로 `"marketComparison"` 캐시를 직접 읽기만 하도록 하고(계산 트리거 없음),
   `PropertyService.getMyProperties()`가 `compare()` 대신 이 메서드를 쓰도록 변경. 캐시 미스면
   `MarketComparisonUnavailableReason.NOT_YET_CALCULATED`로 응답하며, 실제 계산은 등록/수정/상세조회(모두
   단일 매물 호출이라 증폭되지 않음)에서만 트리거된다. FE는 이미 `AVAILABLE`이 아닌 모든 경우를 "실거래가 연동
   예정"으로 처리하고 있어 변경이 필요 없었다.
