# 도메인 전체 구현 현황 — 요약

## 배경

`auth-design.md`, `user-design.md`, `property-design.md`, `market-data-design.md`, `checklist-design.md`, `contract-analysis-design.md`, `risk-analysis-design.md` 7개 문서를 도메인별로 작성하면서 반복적으로 나타난 패턴을 모았습니다. 각 도메인 문서의 "확인 필요" 항목이 개별적으로는 별 것 아니어 보여도, 모아 놓고 보면 같은 원인에서 나온 경우가 많아 여기서 한 번에 짚습니다.

## 도메인별 구현 상태 한눈에

| 도메인 | 상태 | 비고 |
|---|---|---|
| `auth` | 거의 완전 구현 | **(2026-07-28 갱신)** 확인 필요 2개로 축소 — 다중 소셜 연동(`UserSocialAccount`)/jti 블랙리스트/토큰 실패 사유 구분 등 4개 해결. 남은 2개는 코드 문제가 아니라 카카오 email 심사 신청 여부(팀 결정)와 Refresh Token "별도 저장소 없음" 원문 의도(원작성자 확인) |
| `checklist` | 거의 완전 구현 | 요구사항 대비 확인 필요 8개 항목 전부 처리 완료(코드 5건 수정 + 문서화 3건 + 노션 명세서 반영). 거래유형별 분기 미도입만 열려있는 논의 사항으로 남음. **(2026-08-06 추가)** `GET /checklists`(내 체크리스트 목록)에 DB 레벨 페이지네이션 추가 — 매물 목록(`GET /properties`)과 동일한 `PageResponse` 봉투 구조로 응답이 바뀜(기존 배열 응답에서 breaking change) |
| `user` | 거의 완전 구현 | **(2026-08-11 갱신)** 이미지 업로드(S3 presign/confirm/reset), 탈퇴 처리(익명화 + 연관 데이터 정리 + 세션 무효화, FE 연결까지 완료)가 모두 끝났습니다. 남은 건 "생성형 AI 사전고지"/`currentStage` 기반 위젯 우선순위가 FE 책임인지 확인하는 2건과, S3 이전 이미지 정리(태깅+즉시삭제)가 둘 다 실패하는 낮은 확률의 엣지케이스 1건뿐 |
| `property` | 부분 구현, 명세보다 크게 좁음 | 시세·위험신호·체크리스트 연동 전무. **(2026-08-06 정정)** "검색/페이지네이션 없음"은 더 이상 사실이 아님 — `GET /properties`가 지역/면적/가격 등 검색 조건 + `Pageable` 기반 페이지네이션(`PropertyRepository.search()`, `PageResponse`)을 이미 지원함 |
| `contract-analysis` | 부분 구현(진행 중) | 핵심 단계인 AI 분석(`/analyze`) 자체가 없음 |
| `market-data` | 거의 완전 구현 | 반경 기반 실거래가 비교 로직 동작, `property`에 실제 연결됨. 남은 건 FE 연동, 실시간재계산→캐싱 전환 여부 등 4개 |
| `risk-analysis` | 완전 구현 | **(2026-08-04 완료)** 신호 탐지기 4종, API 4개(`POST /risk-analysis`, `GET /risk-signals`, `GET /deposit-safety`, `POST /deposit-safety/recalculate`), market-data 어댑터(전세+매매), `DepositSafetyCheckService`(전세가율), checklist 연계 보조 신호, 매물 수정 시 자동 재계산 트리거(이벤트 기반)까지 전부 구현 완료. 완전 미해결 이슈 없음 — 남은 건 "동일 계정 다수 등록" 활성화 여부·선순위보증금 입력 화면 배치 등 팀/FE 결정 2건뿐 |

## 반복적으로 나타난 패턴

### 1. 정의만 되어 있고 실제로 안 쓰이는 에러코드 (죽은 코드)

`ErrorCode.java`에 있는 도메인별 커스텀 코드 중 다음 8개는 어디에서도 참조되지 않습니다(전체 커스텀 코드 26개 중 약 1/3).

- `property`: `PROPERTY_REQUIRED_FIELD_MISSING`, `PROPERTY_TYPE_NOT_SUPPORTED`, `PROPERTY_IMAGE_INVALID`
- `contract-analysis`: `CONTRACT_ANALYSIS_NOT_RELATED`, `CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED`, `CONTRACT_ANALYSIS_AI_RESPONSE_INVALID`, `CONTRACT_ANALYSIS_AI_HALLUCINATION`, `CONTRACT_ANALYSIS_AI_API_ERROR`

두 도메인 다 "요구사항 명세서를 읽고 실패 사유별로 코드를 미리 다 만들어뒀지만, 정작 그 코드를 던지는 검증 로직은 아직 못 짠" 상태로 보입니다. 특히 contract-analysis의 5개는 전부 아직 없는 `/analyze` 단계용이라 자연스러운 반면, property의 3개는 해당 기능(이미지 검증, 매물유형 검증)이 애초에 스코프에서 빠졌는지 확인이 필요합니다. (**2026-08-06 정정**: 원래 이 목록에 있던 `PROPERTY_INVALID_SEARCH_CONDITION`은 이후 `PropertyService`의 검색/페이지네이션 조건 검증(`getMyProperties`)에서 실제로 4곳에서 던져지고 있어 죽은 코드가 아님을 확인해 제외함.)

### 2. market-data는 해소됨, risk-analysis에는 아직 같은 흔적이 남아있음

`market-data`는 실제로 구현되어 `property`에 연결되었고, `property`의 `marketComparison` 필드도 이제 조건에 따라 실제 시세 값을 채웁니다(더 이상 "항상 UNAVAILABLE 고정값"이 아님). **(2026-08-04 완료)** `risk-analysis`도 신호 탐지기 4종 + 컨트롤러 + market-data 어댑터(전세+매매)에 이어 `DepositSafetyCheckService`(전세가율 계산)까지 전부 실제로 동작합니다 — 이걸 미리 참조하려던 다른 도메인 코드에도 이제 자리표시자가 아닌 실제 데이터 소스가 생겼습니다.

- `property`의 매물 상세 응답엔 위험 신호·안전성 정보를 담을 필드가 여전히 없음 — `risk-analysis` 쪽 데이터 소스(`GET /risk-signals`, `GET /deposit-safety`)는 이제 완전히 갖춰졌으니, `property` 쪽에서 이걸 가져다 쓰는 연동(응답에 필드 추가하거나 프론트가 두 API를 따로 호출하거나)만 하면 됨 — 아직 안 함
- **(2026-08-04 완전 해결)** `checklist`의 소유권취득일 문항에 남아있던 "risk-analysis 전세가율과 연계"라는 주석이 실제 코드로 이어짐 — `DepositSafetyCheckService`가 `ChecklistItemRepository`에 추가된 문항 단건 조회 쿼리로 직접 읽어와 "최근 소유권 변경 + 높은 전세가율" 보조 신호를 계산함
- **(2026-08-03 완전 해결)** `risk-analysis` 내부의 `MarketDataClient`가 market-data 실 구현체(`MarketComparisonService`)와 형태가 안 맞아 연결할 수 없던 문제 — `MarketDataClientImpl` 어댑터로 상태 모델(2단계→3단계) 변환, 메서드 시그니처 변환, 사유 코드 매핑까지 전부 처리해 해결. 이전에 꽂혀 있던 `TemporaryMarketDataClient`(임시로 항상 `UNDETERMINABLE`만 반환하던 자리표시자)는 삭제됨. `property`가 예전에 쓰던 "항상 UNAVAILABLE" 고정값과 비슷한 임시방편이었지만, 이번엔 데이터가 없어서가 아니라 **형태가 안 맞아서** 생긴 자리표시자였다는 점이 달랐음(`risk-analysis-design.md`의 'market-data 도메인 연동' 참고).

셋 다 결국 risk-analysis 쪽의 미구현·형태 불일치가 원인이었는데, **(2026-08-04)** 세 이슈 전부 해결됐습니다. 남은 건 `property` 상세 응답에 위험 신호/전세가율을 실제로 노출하는 연동뿐입니다.

### 3. "매물 수정 시 재계산/무효화" 훅 — 이제 두 도메인 다 해소, 서로 다른 방식을 택함

요구사항 명세서 3곳(`property`, `market-data`, `risk-analysis`)이 공통으로 "가격/거래유형이 바뀌면 기존 시세·위험신호 결과를 무효화하고 재계산한다"를 요구했는데, 두 도메인이 서로 다른 방식으로 해소했습니다. `market-data`는 비교 결과를 아예 저장하지 않고 매물 조회/수정 때마다 즉시 재계산하므로(`MarketComparisonService.compare()`), "무효화할 저장된 상태" 자체가 없어 이 요구사항이 사실상 N/A가 되었습니다(`market-data-design.md` 비기능요구사항 참고). `risk-analysis`는 결과를 `PropertyRisk`/`PropertyRiskCheck`/`DepositSafetyCheck`에 스냅샷으로 저장하는 구조라 재계산 트리거가 꼭 필요했는데, **(2026-08-04 해결)** `property` 담당자와 협의해 **이벤트 기반**으로 풀었습니다 — `PropertyService.update()`가 `PropertyUpdatedEvent`를 발행하고, `RiskRecalculationService`가 `@TransactionalEventListener(AFTER_COMMIT)`로 구독해 재계산을 실행합니다. `property` 패키지는 risk-analysis를 전혀 import하지 않습니다(direct injection 대신 이벤트를 택한 이유는 트랜잭션 롤백 위험 회피 + 도메인 결합 방지 둘 다 — `risk-analysis-design.md` 남은 이슈 8번 참고). 두 도메인이 같은 문제("변경 시 무효화")를 "캐싱 자체를 안 함" vs "이벤트로 비동기 위임"이라는 서로 다른 전략으로 풀었다는 점이, 향후 비슷한 요구사항(예: contract-analysis)이 생겼을 때 참고할 만한 사례입니다.

### 4. 매물 삭제 이후 접근 차단 — checklist는 해소, contract-analysis는 아직 통합 전

`property`는 조회/수정 시 `isDeleted()`를 매번 재검사합니다. `checklist`는 원래 **생성 시점에만** 매물 삭제 여부를 확인하고 조회·항목확인 시점엔 재검사하지 않는 구멍이 있었는데, `checklist-design.md` 후속 작업으로 `getChecklist`/`updateChecklistItem`에도 검사를 추가해 해소했습니다. `contract-analysis`는 매물 소유권 검증 자체가 아직 TODO 상태라(`contract-analysis-design.md` 참고) 이 문제가 드러나지 않았을 뿐이니, `/analyze`와 함께 매물 연동을 구현할 때 같은 실수가 반복되지 않도록 주의가 필요합니다.

### 5. 이미지/파일 업로드 검증이 도메인마다 제각각

- `contract-analysis`: 형식(jpeg/png)·크기(10MB) 검증이 **실제로 구현되어 있음** (세 도메인 중 유일)
- `property`: 검증 로직 없음, 에러코드만 정의(위 1번 참고)
- `user`: **(2026-08-11 정정)** 더 이상 사실이 아님 — S3 presign/confirm 2단계 플로우로 실제 업로드가 구현되어 있고, jpg/jpeg/png·5MB 이하를 presign 단계(사전 검증)와 confirm 단계(실제 업로드된 객체 재검증) 양쪽에서 확인함(`user-design.md` "프로필 이미지 업로드/초기화" 절 참고) — 세 도메인 중 유일하게 업로드 **이전** 시점(presign)에도 선제 검증이 있음

같은 "이미지 업로드 검증"이라는 요구사항이 도메인마다 다르게 해석·구현되어 있습니다. **(2026-08-11 갱신)** 이제 `contract-analysis`/`user` 둘 다 실제로 구현되어 있고, `property`만 검증 로직 없이 에러코드만 정의된 상태로 남아있습니다 — 세 도메인 중 실제로 파일 업로드가 필요한 곳에 아직 없다는 게 이제 `property` 하나로 좁혀졌습니다.

### 6. "존재하지 않음(404)" vs "권한 없음(403)" 구분 방식이 도메인마다 다름

- `property`: 404와 403을 명확히 구분해서 응답 — "권한 없는 사용자에게 존재 여부를 노출하지 않는다"는 비기능요구사항과 방향이 반대일 수 있음
- `checklist`: 원래 소유자가 아니어도 403이 아니라 그냥 404로 처리하던 엔드포인트(`getChecklist`)가 하나 있었는데, `checklist-design.md` 후속 작업으로 나머지 엔드포인트(`updateChecklistItem`/`getChecklistResult`)와 동일하게 403/404를 명시적으로 구분하도록 통일함 — 이제 `property`와 같은 방향(명확히 구분)으로 정리됨
- `user`: **(2026-08-11 정정)** "존재하지 않는 사용자"와 "탈퇴한 사용자"를 구분 안 하던 문제는 해결됨 — 다만 `property`/`checklist`처럼 HTTP 상태 코드(403/404)로 나누는 방식이 아니라, 상태 코드는 여전히 404로 통일한 채 `ErrorCode`(`USER_WITHDRAWN`/`USER_SUSPENDED`)만 구분하는 방식을 택함(`getActiveUserOrThrow()`)

`property`와 `checklist`는 상태 코드(403/404) 자체로 구분하는 방향으로 맞춰졌고, `user`는 상태 코드는 그대로 두고 에러코드/메시지만 구분하는 방향을 택해 — 둘 다 "존재 여부와 사유를 구분해서 응답한다"는 목적은 달성했지만 구현 방식은 여전히 다릅니다. 완전한 팀 공통 정책(어느 계층에서 구분할지)은 아직 없습니다.

### 7. 요구사항 명세서 자체의 내부 모순 (구현 문제가 아님)

`checklist` 요구사항의 "체크리스트 결과확인" 절은 본문(시스템 처리)에서 "시작 전이면 안내 메시지로 대체(성공)"라고 해놓고, 바로 아래 "실패 사유" 목록엔 "확인한 항목 없음"을 실패 케이스로 나열해 서로 모순됩니다. 실제 구현은 본문 설명(성공 처리)을 따랐습니다. 다른 도메인에서도 비슷한 명세서 내부 모순이 있을 수 있으니, 명세서를 다시 다듬을 때 참고할 사례로 남겨둡니다.

### 8. 더 이상 유효하지 않은 TODO

`contract-analysis`의 매물 소유권 검증 TODO는 "Property 엔티티/레포지토리 도입 후 구현"이라고 되어 있지만, `Property`는 이미 다른 도메인에 구현되어 있어 이 TODO는 더 이상 블로킹 사유가 아닙니다. 실제 연동만 하면 되는, 지금 당장 처리 가능한 작업입니다.

## 참고: 각 도메인 문서의 "남은 이슈" 개수

| 도메인 | 확인 필요 항목 수 |
|---|---|
| auth | 2개 (2026-07-28 갱신, 위 표 참고) |
| user | 3개 (2026-08-11 갱신, 8개 중 5개 해결 — 탈퇴 관련 4건 + 프로필 등록 409 정정 1건. 남은 3개는 FE/BE 책임 소재 확인 2건 + S3 이중실패 엣지케이스 1건 — `user-design.md` 참고) |
| property | 17개 |
| market-data | 4개 |
| checklist | 1개 (8개 중 7개 처리 완료, 남은 건 거래유형별 분기 여부뿐) |
| contract-analysis | 8개 |
| risk-analysis | 0개 (2026-08-04 완료, 원본 9개 전부 해결 + 신규 발견 1개도 해결. 남은 건 팀/FE 결정 대기 2건뿐 — `risk-analysis-design.md` 참고) |
