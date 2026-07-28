# 위험도 분석(risk-analysis) 도메인 — 구현 현황 정리

## 배경 / 성격

다른 문서들과 같은 방식의 **회고성(retroactive) 문서**입니다. 이전 버전에서는 "코드 자체가 전혀 없음"으로 정리했지만, 이후 `com.algogyeyak.riskanalysis` 패키지에 **골격(스켈레톤) 코드**가 추가되어 현재는 상황이 달라졌습니다 — 엔티티/enum/repository/정책 설정/오케스트레이션 서비스는 준비되어 있으나, **실제 신호 탐지 로직과 API 노출은 아직 비어 있는 상태**입니다.

## 실제 구현 현황: 스켈레톤 단계 (신호 탐지 로직 없음)

### 준비된 것

- **엔티티**
  - `PropertyRisk` — 매물 하나에 대해 탐지된 위험 신호 1건(`signalType`, `description`, `detectedAt`)을 기록. `riskCheckId`/`propertyId`를 FK 컬럼으로만 들고 JPA 연관관계는 맺지 않음.
  - `PropertyRiskCheck` — 매물당 1행(`property_id` unique 제약)으로 최신 체크 상태를 관리. `RiskCheckStatus`(SUCCESS/UNDETERMINABLE/FAILED) + `RiskCheckReason`(판정불가/실패 사유)을 갖고, 재계산 시 새로 만들지 않고 `overwrite()`로 같은 행을 덮어씀.
  - `DepositSafetyCheck` — 빈 스텁(필드 없음). 전세가율/선순위보증금 검증용 엔티티는 아직 미구현.
- **enum** — `RiskSignalType`(4종: 가격이상치/중복매물/동일계정다수등록/짧은주기재등록), `RiskCheckStatus`, `RiskCheckReason`, `MarketComparisonStatus`, `MarketUnavailableReason`(risk-analysis가 market-data 응답을 소비할 때 쓰는 자체 정의 — market-data 도메인의 실제 enum이 아님).
- **DTO** — `DetectedSignal`(신호 타입+설명, 탐지기 반환용), `MarketComparison`(risk-analysis 전용 뷰 record, market-data의 엔티티가 아님을 주석으로 명시). `RiskSignalResponse`/`DepositSafetyCheckResponse`는 빈 스텁.
- **repository** — `PropertyRiskRepository`(매물별 이력 조회, 재계산 시 전체 삭제용 `deleteByPropertyId`), `PropertyRiskCheckRepository`(매물별 단건 조회).
- **정책 설정** — `RiskPolicyConfig`(`@ConfigurationProperties(prefix = "risk-policy")`). `application.yml`에 `risk-policy:` 블록(`version: v1.0`, `price-anomaly-percent: 10`, `jeonse-ratio-warn-from: 100`, `jeonse-ratio-warn-to: 150`, `jeonse-ratio-alert-over: 150`, `multi-account-detection-enabled: false`)이 추가되어 값 자체는 채워져 있음. 다만 이 값을 실제로 읽어 판정하는 로직(아래 탐지기들)이 없어 **현재는 설정값만 존재하고 아무 효과가 없음**.
  - 정책 버전 이력(과거 버전별 임계값을 DB에 남기는 것)은 Github의 `application.yml` 변경 이력을 확인하는 것으로 임시 설정.
- **오케스트레이션 서비스** — `FakeListingSignalService.checkAndSave(Property)`가 구현되어 있음: `MarketDataClient`로 시세 비교를 가져와 `FAILED`/`UNDETERMINABLE`이면 탐지기를 돌리지 않고 상태만 기록하고, `SUCCESS`면 활성화된(`SignalDetector.isEnabled()`) 탐지기들을 전부 돌려 결과를 모아 `PropertyRisk`로 저장(재계산 시 기존 신호는 삭제 후 재삽입). 다만 **아직 어디서도 호출되지 않음** — 매물 등록/수정 이벤트나 컨트롤러에 연결되어 있지 않음.
- **`MarketDataClient`** — market-data 도메인이 구현될 때까지 쓸 인터페이스만 정의. 실제 구현체가 없으면 이 서비스가 `@Service`로 컴포넌트 스캔되는 순간 스프링 컨텍스트가 뜨지 않으므로, 임시로 `TemporaryMarketDataClient`(`client` 패키지, 항상 `UNDETERMINABLE` 반환)를 꽂아 둠 — TODO 주석으로 market-data 완료 시 삭제 대상임을 표시.

### 여전히 비어 있는 것

- **신호 탐지기 4종 전부 빈 클래스**: `PriceAnomalyDetector`, `DuplicateListingDetector`, `SameAccountMultipleDetector`, `ShortTermRelistingDetector` — `SignalDetector` 인터페이스를 구현하지도 않은 빈 껍데기라 실제로는 `FakeListingSignalService`의 `List<SignalDetector> detectors`에 하나도 주입되지 않음.
- **`RiskAnalysisController`** — 빈 스텁. 엔드포인트가 하나도 없어 이 도메인의 어떤 기능도 API로 노출되지 않음.
- **`DepositSafetyCheckService`, `RiskRecalculationService`** — 빈 스텁. 전세가율/선순위보증금 검증, 매물 수정 시 재계산 트리거 로직 모두 없음.
- 국토교통부 실거래가 자체(=market-data 도메인)도 여전히 없어, `MarketDataClient`의 실제 구현체가 나올 방법이 아직 없음 — risk-analysis는 여전히 market-data에 대해 이중으로 막혀 있는 상태(도메인 골격은 생겼지만, 탐지기도 비어있고 입력으로 쓸 실제 시세 데이터도 없음).

## 다른 도메인에 남아있는 연계 흔적

- `property` 도메인의 매물 상세조회(`PropertyDetailResponse`)엔 위험 신호·안전성 정보를 담을 필드가 아직 없음(`property-design.md` 확인 필요 항목 #3과 동일 사안). `RiskAnalysisController`가 비어 있어 이 필드가 생겨도 채울 데이터 소스가 없는 상태이기도 함.
- `checklist` 도메인의 `ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE`(소유권 취득일 문항)에 "risk-analysis 전세가율과 연계되는 보조 신호용"이라는 주석이 남아있음. 실제로 전세가율 값을 읽어와 조합 판단하는 코드는 여전히 없음(`DepositSafetyCheckService`가 빈 스텁이므로).
- `property`의 매물 삭제는 논리 삭제(soft delete, `PropertyStatus.DELETED`)라 삭제된 매물 데이터가 DB에 남아있음 — `ShortTermRelistingDetector`가 필요로 할 재료(삭제 이력)는 준비돼 있지만, 탐지기 자체가 빈 스텁이라 아직 활용되지 않음.
- `property`의 등록 시 중복 검사(`PROPERTY_DUPLICATE`)는 "동일 사용자가 동일 주소·거래유형으로 재등록"할 때만 걸리는 검사라, `DuplicateListingDetector`가 요구하는 "서비스 전체에서 동일 주소·유사 조건으로 등록된 다른 매물(다른 사용자 포함)" 탐지와는 성격이 달라 재사용이 어려움 — 여전히 별도 쿼리 필요.

## 요구사항 대비 대조

| 요구사항 항목                                  | 실제 상태                                                                                                                                                               |
|------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 가격 이상치 신호(시세 대비 10~20% 저렴)               | ❌ 미구현 — `PriceAnomalyDetector` 빈 스텁. `RiskPolicyConfig.priceAnomalyPercent` 설정값은 있으나 소비하는 로직 없음                                                                     |
| 동일 주소·유사 조건 중복매물 탐지                      | ❌ 미구현 — `DuplicateListingDetector` 빈 스텁                                                                                                                             |
| 동일 계정 다수 등록 탐지 (🔶 논의중, 정책 플래그로 온/오프 요구) | ❌ 미구현 — `SameAccountMultipleDetector` 빈 스텁. 다만 온/오프 골격(`RiskPolicyConfig.multiAccountDetectionEnabled` + `SignalDetector.isEnabled()`)은 준비됨                         |
| 짧은 주기 재등록 탐지                             | ❌ 미구현 — `ShortTermRelistingDetector` 빈 스텁. 필요한 원재료(soft delete 이력)는 이미 있음                                                                                           |
| 전세가율 계산(기본/선순위보증금 반영/근저당 채권최고액 반영)       | ❌ 미구현 — `DepositSafetyCheck` 엔티티가 빈 스텁                                                                                                                              |
| 선순위보증금 검증(100~150% 강조 안내, 150% 초과 경고)    | ❌ 미구현 — 다만 `RiskPolicyConfig`에 `jeonseRatioWarnFrom/To`, `jeonseRatioAlertOver` 값은 이미 채워짐                                                                           |
| "최근 소유권 변경 + 높은 전세가율" 보조 신호              | ❌ 미구현 — `checklist` 쪽 연계 의도 주석만 있음(위 참고)                                                                                                                            |
| 성공/판정불가/실패 3단계 응답 구조                     | ⚠️ 모델 수준만 구현 — `PropertyRiskCheck.status`(`RiskCheckStatus`)로 내부적으로는 3단계를 구분하지만, 이를 클라이언트에 내려주는 `RiskAnalysisController`/`RiskSignalResponse`가 빈 스텁이라 API로는 노출되지 않음 |
| 정책 버전 기록                                 | ⚠️ 부분 구현 — `PropertyRiskCheck.policyVersion`에 체크 시점의 `RiskPolicyConfig.version` 문자열을 스냅샷으로 남기지만, 버전별 임계값 이력을 DB에 남기는 기능은 없음                                         |
| 위험 신호 재계산(매물 수정 시 트리거)                   | ❌ 미구현 — `RiskRecalculationService` 빈 스텁, `property` 수정(`PATCH /properties/{id}`)에도 트리거 지점 없음(`property-design.md`, `market-data-design.md`에서 이미 확인된 사안과 동일)         |

## 비기능 요구사항 — 대조

| 항목 | 상태 |
|---|---|
| 동일 입력·동일 정책 버전이면 동일 결과 보장 | ❌ 탐지 로직 자체가 빈 스텁이라 검증 불가 |
| 위험도 계산 실패가 매물 상세 조회 전체 실패로 이어지지 않음 | ⚠️ `FakeListingSignalService`는 market-data 실패/판정불가를 예외 없이 상태값으로 흡수하지만, `PropertyService.getProperty()` 쪽에 이 결과를 읽어오는 연동 지점 자체가 없어 실제 조회 흐름에서 검증 불가 |
| 실거래가 조회 실패와 위험도 계산 실패 구분 | ⚠️ 모델 수준에서는 `RiskCheckReason.DATA_FETCH_FAILURE`(FAILED)와 `NO_COMPARABLE_TRANSACTION`(UNDETERMINABLE)로 구분되지만, `MarketComparison.reason()`(market-data 쪽 세부 사유, `MarketUnavailableReason`)은 `FakeListingSignalService`가 그대로 넘기지 않고 뭉뚱그려 매핑해 세부 사유가 소실됨 |
| 매물 정보/시세 변경 시에만 재계산(불필요한 재계산 방지) | ❌ 미구현 — 재계산을 트리거할 지점(`property` 수정 API, `RiskRecalculationService`) 모두 없음 |
| 동일계정 다수등록 탐지를 정책 플래그로 켜고 끌 수 있게 | ⚠️ 설정/골격(`RiskPolicyConfig.multiAccountDetectionEnabled`, `SignalDetector.isEnabled()`)은 준비되었으나 탐지 로직이 없어 실질적으로 검증 불가 |
| 서비스 대상 범위(전세+월세)와 안전성 체크 적용 범위(전세만) 화면 안내 구분 | ❌ 미구현 — 응답 자체(`RiskAnalysisController`)가 없음 |

## 남은 이슈 / 확인 필요 총정리

1. **신호 탐지 로직 4종(`PriceAnomalyDetector`, `DuplicateListingDetector`, `SameAccountMultipleDetector`, `ShortTermRelistingDetector`)이 전부 빈 스텁** — `SignalDetector`를 구현하지 않아 오케스트레이션 서비스에 주입되지도 않음. 우선순위와 구현 일정 확인 필요.
2. **`RiskAnalysisController`가 비어 있어 API로 노출되는 기능이 없음** — 골격이 갖춰진 다른 부분(엔티티/서비스)도 컨트롤러 없이는 검증 불가.
3. market-data 의존성 — 실제 `MarketDataClient` 구현체가 없어 임시로 `TemporaryMarketDataClient`(client 패키지, 항상 `UNDETERMINABLE` 반환)를 꽂아둔 상태. market-data 도메인 완료 시 반드시 교체/삭제 필요(TODO 주석 있음). 두 도메인의 구현 순서를 어떻게 잡을지 여전히 확인 필요.
4. `checklist` 도메인에 남아있는 "전세가율 연계" 주석 외에는 실제 연동 코드가 없음 — `DepositSafetyCheckService` 구현 시 checklist의 소유권 취득일 값을 어떻게 읽어올지(체크리스트 조회 API 호출? 직접 쿼리?) 설계 필요.
5. `property`의 기존 중복 등록 검사(동일 사용자 한정)와 `DuplicateListingDetector`가 요구하는 신호(다른 사용자 포함 가능)는 서로 다른 메커니즘이라 재사용이 어려워 보임 — 별도 쿼리/로직 필요.
6. "동일 계정 다수 등록 탐지"는 요구사항 문서 자체가 🔶 논의중이라고 표시한 항목 — 온/오프 골격(`multiAccountDetectionEnabled`)은 준비됐지만, Property 도메인이 "임대인이 아닌 일반 사용자가 검토용으로 등록"하는 구조라는 점 때문에 이 신호가 원래 의도(임대인/중개사 어뷰징 탐지)대로 작동하지 않을 수 있다는 점은 여전히 팀 결정 대기.
7. "선순위보증금을 매물 등록 시점에 받을지, 임장 체크리스트의 서류·행정 카테고리에서 받을지" 화면 흐름이 여전히 미확정 — checklist 쪽 UX와 맞물린 결정 필요.
8. 위험 신호 재계산을 트리거할 지점(매물 수정 시)이 `property` 도메인에도, risk-analysis 쪽(`RiskRecalculationService`)에도 아직 없음 — market-data 쪽과 함께 설계해야 하는 공통 훅.
9. `risk-policy` 설정값(`price-anomaly-percent: 10` 등)이 `application.yml`에 채워졌지만, 이를 실제로 소비하는 판정 로직이 아직 없어 현재는 설정값만 존재하고 동작에는 영향이 없음 — 탐지기 구현 시 반드시 이 값을 참조하도록 연결해야 함.