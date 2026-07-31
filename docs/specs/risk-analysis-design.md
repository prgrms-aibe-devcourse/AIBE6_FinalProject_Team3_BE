# 위험도 분석(risk-analysis) 도메인 — 구현 현황 정리

## 배경 / 성격

다른 문서들과 같은 방식의 **회고성(retroactive) 문서**입니다. 이전 버전에서는 "코드 자체가 전혀 없음"으로 정리했지만, 이후 `com.algogyeyak.riskanalysis` 패키지에 **골격(스켈레톤) 코드**가 추가되어 현재는 상황이 달라졌습니다 — 엔티티/enum/repository/정책 설정/오케스트레이션 서비스는 준비되어 있으나, **실제 신호 탐지 로직과 API 노출은 아직 비어 있는 상태**입니다.

## 실제 구현 현황: 스켈레톤 단계 (신호 탐지 로직 없음)

### 준비된 것

- **엔티티**
  - `PropertyRisk` — **매물×신호타입 조합당 1행**(`(property_id, signal_type)` unique 제약)으로 최신 탐지 결과 1건(`description`)을 기록. **(변경)** `risk_check_id`(FK)는 제거됨 — `PropertyRiskCheck`와 같은 자연키(`property_id`+`signal_type`)를 공유하고 같은 트랜잭션에서 upsert되므로 대리키로 연결할 필요가 없어짐(기존에도 어디서도 읽히지 않던 write-only 컬럼이었음). `detectedAt`도 제거(새 스키마에 없음). `property`는 `@ManyToOne`+`@JoinColumn`으로 실제 FK — 이전엔 `propertyId`를 bare `Long` 컬럼으로만 들고 JPA 연관관계를 맺지 않았음.
  - `PropertyRiskCheck` — **매물×신호타입 조합당 1행**(`(property_id, signal_type)` unique 제약)으로 최신 체크 상태를 관리. `RiskCheckStatus`(SUCCESS/UNDETERMINABLE/FAILED) + `RiskCheckReason`(판정불가/실패 사유)을 신호 4종마다 독립적으로 갖고, 재계산 시 새로 만들지 않고 `overwrite()`로 같은 행을 덮어씀. (이전 버전은 `property_id` 단독 unique라 매물 전체에 상태 하나뿐이었는데, 신호별로 분리됨 — 아래 오케스트레이션 서비스 참고) **(변경)** `property`도 `PropertyRisk`와 마찬가지로 bare `Long propertyId`에서 `@ManyToOne`+`@JoinColumn` 실제 FK로 변경됨 — `checklist.Checklist`가 이미 쓰던 컨벤션과 통일.
  - `DepositSafetyCheck` — **(신규) 구현 완료**. 매물당 1행(`property_id` 단독 unique, `@ManyToOne` FK)으로 전세가율 계산 결과를 관리. `jeonseRatio`(계산 결과, CALCULATED일 때만 존재)·`seniorDeposit`/`maxClaimAmount`(선택 입력, status와 무관하게 항상 받을 수 있음)·`explanation`·`policyVersion`을 갖고, `DepositSafetyStatus`(CALCULATED/UNAVAILABLE/FAILED) + `DepositSafetyCheckReason`(판정불가/실패 사유)으로 `PropertyRiskCheck`와 동일한 상태 모델을 따름. 정적 팩토리(`calculated`/`unavailable`/`failed`) + `overwrite()`로 재계산 시 덮어쓰는 패턴도 동일. **다만 이 엔티티를 실제로 채우는 계산 로직(`DepositSafetyCheckService`)은 여전히 빈 스텁** — 데이터 모델만 준비된 상태.
- **enum** — `RiskSignalType`(4종: 가격이상치/중복매물/동일계정다수등록/짧은주기재등록), `RiskCheckStatus`, `RiskCheckReason`, **(신규)** `DepositSafetyStatus`(CALCULATED/UNAVAILABLE/FAILED), **(신규)** `DepositSafetyCheckReason`(전세가율 판정불가/실패 사유 — `RiskCheckReason`과 같은 패턴으로 신설), `MarketComparisonStatus`, `MarketUnavailableReason`(risk-analysis가 market-data 응답을 소비할 때 쓰는 자체 정의 — market-data 도메인의 실제 enum이 아님).
- **DTO** — `MarketComparison`(risk-analysis 전용 뷰 record, market-data의 엔티티가 아님을 주석으로 명시), `SignalCheckResult`(status/reason/description — 탐지기 하나가 자신의 SUCCESS/UNDETERMINABLE/FAILED와 설명 1건을 독립적으로 표현하기 위해 신설). **(변경)** `description`은 원래 `List<DetectedSignal>`이었으나, `property_risks`가 `(property_id, signal_type)` unique 제약을 갖도록 스키마가 확정되면서(신호 하나당 row 1개만 허용) 단일 `String`으로 축소됨 — 탐지기가 여러 건을 찾아도(예: 중복매물 2건) 하나의 문장으로 합쳐서 반환해야 함. 더는 안 쓰이는 `DetectedSignal` DTO는 삭제됨. `RiskSignalResponse`/`DepositSafetyCheckResponse`는 빈 스텁.
- **repository** — `PropertyRiskRepository`(`findByPropertyIdAndSignalType`로 upsert 대상 단건 조회, `findAllByPropertyId`로 매물의 신호 결과 전체 조회, 신호가 더 이상 감지되지 않을 때 지우는 `deleteByPropertyIdAndSignalType`), `PropertyRiskCheckRepository`(`findByPropertyIdAndSignalType`로 신호별 단건 조회, `findAllByPropertyId`로 매물의 4개 신호 결과 전체 조회). 두 리포지토리 모두 `propertyId` 파라미터 타입은 그대로 `Long`이지만, 엔티티가 `property` 연관관계로 바뀌면서 Spring Data가 `property.id`로 자동 해석함(메서드명 변경 불필요 — 컨텍스트 로드 테스트로 확인).
- **정책 설정** — `RiskPolicyConfig`(`@ConfigurationProperties(prefix = "risk-policy")`). `application.yml`에 `risk-policy:` 블록(`version: v1.0`, `price-anomaly-percent: 10`, `jeonse-ratio-warn-from: 100`, `jeonse-ratio-warn-to: 150`, `jeonse-ratio-alert-over: 150`, `multi-account-detection-enabled: false`)이 추가되어 값 자체는 채워져 있음. 다만 이 값을 실제로 읽어 판정하는 로직(아래 탐지기들)이 없어 **현재는 설정값만 존재하고 아무 효과가 없음**.
  - 정책 버전 이력(과거 버전별 임계값을 DB에 남기는 것)은 Github의 `application.yml` 변경 이력을 확인하는 것으로 임시 설정.
- **오케스트레이션 서비스** — `FakeListingSignalService.checkAndSave(Property)`가 구현되어 있음. **신호별 독립 판정 구조로 변경됨**: 이전 버전은 시세비교(`MarketComparison`)가 `FAILED`/`UNDETERMINABLE`이면 4개 탐지기를 아예 돌리지 않고 매물 전체를 판정불가/실패로 기록했는데(중복매물·동일계정·재등록처럼 시세비교와 무관한 신호까지 같이 막혀버리는 문제가 있었음), 지금은 시세비교를 한 번만 조회해서 활성화된(`SignalDetector.isEnabled()`) 탐지기 4개 모두에게 넘기고, 그 결과를 각 탐지기가 `SignalCheckResult`(SUCCESS/UNDETERMINABLE/FAILED + 사유 + 탐지된 신호 목록)로 독립적으로 반환하도록 바뀜. 신호 하나가 판정불가/실패여도 나머지 신호는 영향받지 않고, 결과는 `(propertyId, signalType)` 단위로 `PropertyRiskCheck`에 upsert되고, `PropertyRisk`도 같은 키로 upsert됨(있으면 `description`을 `overwrite()`로 덮어쓰고, 없으면 새로 insert) — 리스크가 해소되거나 판정불가/실패로 바뀌면 기존 row를 삭제(`deleteByPropertyIdAndSignalType`)함. **(변경)** 원래는 매번 delete 후 탐지된 신호 리스트 전체를 재삽입하는 방식이었으나, `property_risks`가 `(property_id, signal_type)` unique 제약을 갖게 되면서 "신호당 row 1개"만 허용돼 이 upsert 방식으로 바뀜 — 다른 신호가 이미 찾아둔 결과를 덮어쓰지 않는 점은 동일. 다만 **아직 어디서도 호출되지 않음** — 매물 등록/수정 이벤트나 컨트롤러에 연결되어 있지 않음.
- **`SignalDetector`** — `detect()`의 반환 타입이 `List<DetectedSignal>` → `SignalCheckResult`로 변경됨. `comparison`은 여전히 4개 탐지기 모두에게 파라미터로 전달되지만, 실제로 이 값을 참고해야 하는 건 `PriceAnomalyDetector` 하나뿐이고 나머지 3개는 무시하도록 설계됨(인터페이스를 둘로 쪼개는 대신 단일 리스트로 오케스트레이션을 단순하게 유지하는 절충).
- **`MarketDataClient`** — risk-analysis가 정의한 인터페이스. 실제 구현체가 없으면 `FakeListingSignalService`가 `@Service`로 컴포넌트 스캔되는 순간 스프링 컨텍스트가 뜨지 않으므로, 임시로 `TemporaryMarketDataClient`(`client` 패키지, 항상 `UNDETERMINABLE` 반환)를 꽂아 둠. market-data 도메인이 이제 실제로 구현됐지만(아래 참고) 형태가 달라 바로 연결할 수 없어 이 임시 구현체는 여전히 필요함 — "market-data 완료 시 삭제"라는 TODO 주석의 전제 자체를 다시 봐야 함.

### 여전히 비어 있는 것

- **신호 탐지기 4종 전부 빈 클래스**: `PriceAnomalyDetector`, `DuplicateListingDetector`, `SameAccountMultipleDetector`, `ShortTermRelistingDetector` — `SignalDetector` 인터페이스를 구현하지도 않은 빈 껍데기라 실제로는 `FakeListingSignalService`의 `List<SignalDetector> detectors`에 하나도 주입되지 않음.
- **`RiskAnalysisController`** — 빈 스텁. 엔드포인트가 하나도 없어 이 도메인의 어떤 기능도 API로 노출되지 않음.
- **`DepositSafetyCheckService`, `RiskRecalculationService`** — 빈 스텁. 전세가율/선순위보증금 검증, 매물 수정 시 재계산 트리거 로직 모두 없음. **(참고)** `DepositSafetyCheck` 엔티티(데이터 모델)는 구현됐지만, 그 값을 계산해서 채워 넣는 로직은 여전히 이 스텁 상태.

### market-data 도메인은 실제로 구현됨 — 다만 risk-analysis와 형태가 다름 (신규 확인 사항)

이전 버전 문서는 "market-data 자체가 없다"고 정리했지만, 이후 market-data 도메인이 실제로 구현되었습니다(`MarketComparisonService`, `MolitRentClientImpl` 등). 국토부 실거래가 조회 → 전세만 필터링 → 면적오차 ±20% → 반경 300m→600m 확장 → 중앙값 계산까지 동작하고, 이미 `property` 도메인(`PropertyService.register/getProperty/update`)에 연결되어 매물 등록·조회·수정 응답에 실시간으로 반영되고 있습니다.

다만 이건 risk-analysis를 위해 만들어진 게 아니라 **`property`의 매물 상세/등록 응답에 시세비교 정보를 보여주기 위한 것**이라, risk-analysis의 `MarketDataClient` 인터페이스·`MarketComparison` DTO와는 형태가 맞지 않습니다:

- **메서드 시그니처가 다름** — `MarketComparisonService.compare(Property)`는 엔티티를 직접 받는데, risk-analysis의 `MarketDataClient.getComparison(Long propertyId)`는 ID만 받음. 어댑터가 Property를 다시 조회해야 함(간단한 문제).
- **상태 모델 자체가 다름** — market-data의 `MarketComparisonResponse`는 `status`가 `"AVAILABLE"`/`"UNAVAILABLE"` 2단계 + 사용자 안내용 자유텍스트 `message` 뿐인 반면, risk-analysis의 `MarketComparison`은 `SUCCESS`/`UNDETERMINABLE`/`FAILED` 3단계 + 구조화된 사유 enum(`MarketUnavailableReason`)을 기대함. 2단계 → 3단계로 변환하는 규칙이 없음.
- **"판정불가"와 "실패"가 원천 데이터에서부터 구분되지 않음** — `MolitRentClientImpl.fetch()`는 국토부 API 호출 실패(`RestClientException`/`IOException`) 시 예외를 던지지 않고 빈 리스트를 반환함(`Collections.emptyList()`). `MarketComparisonService` 입장에서는 "표본이 원래 없는 경우"와 "API 장애로 못 가져온 경우"가 똑같이 "인근 실거래 데이터가 부족해요" UNAVAILABLE로 보임 — risk-analysis가 요구하는 `FAILED`(외부 API 장애) vs `UNDETERMINABLE`(표본 부족) 구분은 아무리 잘 만든 어댑터로도 복원 불가능하고, market-data 쪽에서 먼저 실패와 판정불가를 구분해 노출해야 함.
- **필드 타입도 다름** — `referencePrice`(Long vs BigDecimal), `differenceRate`(Double vs BigDecimal), `referenceDate`(String "yyyy-MM-dd" vs LocalDate) 등 매핑 시 변환 필요. `askingPrice`는 market-data 응답에 없지만 `Property.getDeposit()`으로 어댑터에서 쉽게 채울 수 있어 이건 사소한 문제.
- 참고로 market-data는 매물 조회/수정 때마다 캐싱 없이 즉시 재계산하므로(`marketComparisonService.compare(property)`), market-data 자신에게는 "재계산 트리거" 문제가 없음 — 이건 risk-analysis처럼 결과를 DB에 스냅샷으로 저장하는 쪽에만 해당되는 문제.
- (신규) 신호별 독립 판정 구조로 바뀌면서, 이 연동 격차의 영향 범위도 줄어들었음 — 시세비교 데이터가 실제로 필요한 신호는 `PriceAnomalyDetector` 하나뿐이고, 나머지 3개(중복매물/동일계정/재등록)는 `MarketDataClient`·어댑터 문제와 무관하게 독자적으로 구현·동작 가능함. 즉 market-data 연동이 안 끝나도 이 3개 탐지기는 먼저 구현해서 값을 낼 수 있음.

## 다른 도메인에 남아있는 연계 흔적

- `property` 도메인의 매물 상세조회(`PropertyDetailResponse`)엔 위험 신호·안전성 정보를 담을 필드가 아직 없음(`property-design.md` 확인 필요 항목 #3과 동일 사안). `RiskAnalysisController`가 비어 있어 이 필드가 생겨도 채울 데이터 소스가 없는 상태이기도 함.
- `checklist` 도메인의 `ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE`(소유권 취득일 문항)에 "risk-analysis 전세가율과 연계되는 보조 신호용"이라는 주석이 남아있음. 실제로 전세가율 값을 읽어와 조합 판단하는 코드는 여전히 없음(`DepositSafetyCheckService`가 빈 스텁이므로).
- `property`의 매물 삭제는 논리 삭제(soft delete, `PropertyStatus.DELETED`)라 삭제된 매물 데이터가 DB에 남아있음 — `ShortTermRelistingDetector`가 필요로 할 재료(삭제 이력)는 준비돼 있지만, 탐지기 자체가 빈 스텁이라 아직 활용되지 않음.
- `property`의 등록 시 중복 검사(`PROPERTY_DUPLICATE`)는 "동일 사용자가 동일 주소·거래유형으로 재등록"할 때만 걸리는 검사라, `DuplicateListingDetector`가 요구하는 "서비스 전체에서 동일 주소·유사 조건으로 등록된 다른 매물(다른 사용자 포함)" 탐지와는 성격이 달라 재사용이 어려움 — 여전히 별도 쿼리 필요.

## 요구사항 대비 대조

| 요구사항 항목                                  | 실제 상태                                                                                                                                                               |
|------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 가격 이상치 신호(시세 대비 10~20% 저렴)               | ❌ 미구현 — `PriceAnomalyDetector` 빈 스텁. 오피스텔/다세대 전세는 이제 실제 시세 데이터(`MarketComparisonService`)가 존재하지만 `MarketDataClient` 어댑터가 없어 연결 불가(위 'market-data 연동 격차' 참고). `RiskPolicyConfig.priceAnomalyPercent` 설정값은 있으나 소비하는 로직 없음 |
| 동일 주소·유사 조건 중복매물 탐지                      | ❌ 미구현 — `DuplicateListingDetector` 빈 스텁                                                                                                                             |
| 동일 계정 다수 등록 탐지 (🔶 논의중, 정책 플래그로 온/오프 요구) | ❌ 미구현 — `SameAccountMultipleDetector` 빈 스텁. 다만 온/오프 골격(`RiskPolicyConfig.multiAccountDetectionEnabled` + `SignalDetector.isEnabled()`)은 준비됨                         |
| 짧은 주기 재등록 탐지                             | ❌ 미구현 — `ShortTermRelistingDetector` 빈 스텁. 필요한 원재료(soft delete 이력)는 이미 있음                                                                                           |
| 전세가율 계산(기본/선순위보증금 반영/근저당 채권최고액 반영)       | ❌ 미구현 — `DepositSafetyCheck` 엔티티(데이터 모델)는 구현됨(`jeonseRatio`/`seniorDeposit`/`maxClaimAmount`/`policyVersion`/`DepositSafetyStatus`/`DepositSafetyCheckReason`), 실제 계산 로직(`DepositSafetyCheckService`)은 여전히 빈 스텁                                                                                       |
| 선순위보증금 검증(100~150% 강조 안내, 150% 초과 경고)    | ❌ 미구현 — 다만 `RiskPolicyConfig`에 `jeonseRatioWarnFrom/To`, `jeonseRatioAlertOver` 값은 이미 채워짐                                                                           |
| "최근 소유권 변경 + 높은 전세가율" 보조 신호              | ❌ 미구현 — `checklist` 쪽 연계 의도 주석만 있음(위 참고)                                                                                                                            |
| 성공/판정불가/실패 3단계 응답 구조                     | ⚠️ 모델 수준 구현이 신호별로 더 정교해짐 — `PropertyRiskCheck.status`(`RiskCheckStatus`)를 이제 신호 4종마다 독립적으로 관리(`(property_id, signal_type)` unique)해서, 신호 하나가 판정불가/실패여도 나머지 신호 결과에 영향 없음. 다만 이를 클라이언트에 내려주는 `RiskAnalysisController`/`RiskSignalResponse`가 여전히 빈 스텁이라 API로는 노출되지 않음 |
| 정책 버전 기록                                 | ⚠️ 부분 구현 — `PropertyRiskCheck.policyVersion`에 체크 시점의 `RiskPolicyConfig.version` 문자열을 스냅샷으로 남기지만, 버전별 임계값 이력을 DB에 남기는 기능은 없음                                         |
| 위험 신호 재계산(매물 수정 시 트리거)                   | ❌ 미구현 — `RiskRecalculationService` 빈 스텁, `property` 수정(`PATCH /properties/{id}`)에도 트리거 지점 없음. 다만 market-data는 캐싱 없이 매번 즉시 재계산하므로 이 문제가 없어졌고(위 참고), risk-analysis는 결과를 DB에 스냅샷으로 저장하는 구조라 여전히 별도 트리거가 필요함           |

## 비기능 요구사항 — 대조

| 항목 | 상태 |
|---|---|
| 동일 입력·동일 정책 버전이면 동일 결과 보장 | ❌ 탐지 로직 자체가 빈 스텁이라 검증 불가 |
| 위험도 계산 실패가 매물 상세 조회 전체 실패로 이어지지 않음 | ⚠️ `FakeListingSignalService`는 market-data 실패/판정불가를 예외 없이 상태값으로 흡수하지만, `PropertyService.getProperty()` 쪽에 이 결과를 읽어오는 연동 지점 자체가 없어 실제 조회 흐름에서 검증 불가 |
| 실거래가 조회 실패와 위험도 계산 실패 구분 | ⚠️ 모델 수준에서는 `RiskCheckReason.DATA_FETCH_FAILURE`(FAILED)와 `NO_COMPARABLE_TRANSACTION`(UNDETERMINABLE)로 구분되지만, 실제 market-data 구현(`MolitRentClientImpl`)이 외부 API 실패를 예외로 던지지 않고 빈 리스트로 삼켜버려 "실패"와 "판정불가"가 원천 데이터부터 구분되지 않음 — 어댑터만으로는 해결 불가, market-data 쪽 선행 작업 필요(위 'market-data 연동 격차' 참고). 신호별 독립 판정으로 바뀌면서 이 문제의 영향 범위는 `PriceAnomalyDetector` 하나로 한정됨 — 나머지 3개 신호는 이 이슈와 무관하게 구현 가능 |
| 매물 정보/시세 변경 시에만 재계산(불필요한 재계산 방지) | ❌ 미구현 — 재계산을 트리거할 지점(`property` 수정 API, `RiskRecalculationService`) 모두 없음 |
| 동일계정 다수등록 탐지를 정책 플래그로 켜고 끌 수 있게 | ⚠️ 설정/골격(`RiskPolicyConfig.multiAccountDetectionEnabled`, `SignalDetector.isEnabled()`)은 준비되었으나 탐지 로직이 없어 실질적으로 검증 불가 |
| 서비스 대상 범위(전세+월세)와 안전성 체크 적용 범위(전세만) 화면 안내 구분 | ❌ 미구현 — 응답 자체(`RiskAnalysisController`)가 없음 |

## 남은 이슈 / 확인 필요 총정리

1. **신호 탐지 로직 4종(`PriceAnomalyDetector`, `DuplicateListingDetector`, `SameAccountMultipleDetector`, `ShortTermRelistingDetector`)이 전부 빈 스텁** — `SignalDetector`를 구현하지 않아 오케스트레이션 서비스에 주입되지도 않음. 인터페이스(`detect()` → `SignalCheckResult` 반환)와 오케스트레이션(신호별 독립 실행)은 준비돼 있으니, 남은 건 각 탐지기의 실제 판정 로직뿐. `PriceAnomalyDetector`만 market-data 어댑터가 필요하고 나머지 3개는 독립적으로 먼저 구현 가능(아래 3번 참고). 우선순위와 구현 일정 확인 필요.
2. **`RiskAnalysisController`가 비어 있어 API로 노출되는 기능이 없음** — 골격이 갖춰진 다른 부분(엔티티/서비스)도 컨트롤러 없이는 검증 불가.
3. market-data 연동 — market-data 도메인이 실제로 구현되어 `property`에는 이미 연결되어 있지만, risk-analysis의 `MarketDataClient` 인터페이스와는 상태 모델(2단계 vs 3단계)·메서드 시그니처·필드 타입이 달라 그대로 쓸 수 없음. 특히 market-data가 "API 실패"와 "표본 부족"을 구분해서 넘겨주지 않아, 단순 어댑터로는 risk-analysis가 요구하는 FAILED/UNDETERMINABLE 구분을 만들 수 없음 — market-data 쪽에 실패 사유를 구분해 노출하는 선행 작업부터 필요. `TemporaryMarketDataClient`는 이 작업이 끝나기 전까지 계속 유지. **(신규)** 다만 신호별 독립 판정 구조로 바뀌면서 이 문제는 더 이상 도메인 전체를 막지 않음 — `PriceAnomalyDetector`만 이 연동이 필요하고, `DuplicateListingDetector`/`SameAccountMultipleDetector`/`ShortTermRelistingDetector`는 market-data와 무관하게 먼저 구현·배포할 수 있음.
4. `checklist` 도메인에 남아있는 "전세가율 연계" 주석 외에는 실제 연동 코드가 없음 — `DepositSafetyCheckService` 구현 시 checklist의 소유권 취득일 값을 어떻게 읽어올지(체크리스트 조회 API 호출? 직접 쿼리?) 설계 필요.
5. `property`의 기존 중복 등록 검사(동일 사용자 한정)와 `DuplicateListingDetector`가 요구하는 신호(다른 사용자 포함 가능)는 서로 다른 메커니즘이라 재사용이 어려워 보임 — 별도 쿼리/로직 필요.
6. "동일 계정 다수 등록 탐지"는 요구사항 문서 자체가 🔶 논의중이라고 표시한 항목 — 온/오프 골격(`multiAccountDetectionEnabled`)은 준비됐지만, Property 도메인이 "임대인이 아닌 일반 사용자가 검토용으로 등록"하는 구조라는 점 때문에 이 신호가 원래 의도(임대인/중개사 어뷰징 탐지)대로 작동하지 않을 수 있다는 점은 여전히 팀 결정 대기.
7. "선순위보증금을 매물 등록 시점에 받을지, 임장 체크리스트의 서류·행정 카테고리에서 받을지" 화면 흐름이 여전히 미확정 — checklist 쪽 UX와 맞물린 결정 필요.
8. 위험 신호 재계산을 트리거할 지점(매물 수정 시)이 `property` 도메인에도, risk-analysis 쪽(`RiskRecalculationService`)에도 아직 없음 — market-data 쪽과 함께 설계해야 하는 공통 훅.
9. `risk-policy` 설정값(`price-anomaly-percent: 10` 등)이 `application.yml`에 채워졌지만, 이를 실제로 소비하는 판정 로직이 아직 없어 현재는 설정값만 존재하고 동작에는 영향이 없음 — 탐지기 구현 시 반드시 이 값을 참조하도록 연결해야 함.