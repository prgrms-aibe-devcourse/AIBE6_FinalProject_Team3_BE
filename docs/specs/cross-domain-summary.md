# 도메인 전체 구현 현황 — 요약

## 배경

`auth-design.md`, `user-design.md`, `property-design.md`, `market-data-design.md`, `checklist-design.md`, `contract-analysis-design.md`, `risk-analysis-design.md` 7개 문서를 도메인별로 작성하면서 반복적으로 나타난 패턴을 모았습니다. 각 도메인 문서의 "확인 필요" 항목이 개별적으로는 별 것 아니어 보여도, 모아 놓고 보면 같은 원인에서 나온 경우가 많아 여기서 한 번에 짚습니다.

## 도메인별 구현 상태 한눈에

| 도메인 | 상태 | 비고 |
|---|---|---|
| `auth` | 거의 완전 구현 | 확인 필요 6개, 대부분 세부 정책(OAuthAccount 부재, 토큰 저장 방식 등) |
| `checklist` | 거의 완전 구현 | 확인 필요 7개, 대부분 명세서와의 세부 불일치(정렬/문구 방향) |
| `user` | 부분 구현 | 이미지 업로드 자체가 없음, 탈퇴 처리 미완성 |
| `property` | 부분 구현, 명세보다 크게 좁음 | 시세·위험신호·체크리스트 연동 전무, 검색/페이지네이션 없음 |
| `contract-analysis` | 부분 구현(진행 중) | 핵심 단계인 AI 분석(`/analyze`) 자체가 없음 |
| `market-data` | 거의 완전 구현 | 반경 기반 실거래가 비교 로직 동작, `property`에 실제 연결됨. 남은 건 FE 연동, 실시간재계산→캐싱 전환 여부 등 4개 |
| `risk-analysis` | 부분 구현(스켈레톤) | 엔티티/enum/정책설정/오케스트레이션 서비스는 있으나 신호 탐지기 4종 전부 빈 스텁, 컨트롤러도 없어 API로 노출되는 기능 없음. market-data에도 의존 |

## 반복적으로 나타난 패턴

### 1. 정의만 되어 있고 실제로 안 쓰이는 에러코드 (죽은 코드)

`ErrorCode.java`에 있는 도메인별 커스텀 코드 중 다음 9개는 어디에서도 참조되지 않습니다(전체 커스텀 코드 26개 중 약 1/3).

- `property`: `PROPERTY_REQUIRED_FIELD_MISSING`, `PROPERTY_TYPE_NOT_SUPPORTED`, `PROPERTY_IMAGE_INVALID`, `PROPERTY_INVALID_SEARCH_CONDITION`
- `contract-analysis`: `CONTRACT_ANALYSIS_NOT_RELATED`, `CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED`, `CONTRACT_ANALYSIS_AI_RESPONSE_INVALID`, `CONTRACT_ANALYSIS_AI_HALLUCINATION`, `CONTRACT_ANALYSIS_AI_API_ERROR`

두 도메인 다 "요구사항 명세서를 읽고 실패 사유별로 코드를 미리 다 만들어뒀지만, 정작 그 코드를 던지는 검증 로직은 아직 못 짠" 상태로 보입니다. 특히 contract-analysis의 5개는 전부 아직 없는 `/analyze` 단계용이라 자연스러운 반면, property의 4개는 해당 기능(이미지 검증, 검색 조건, 매물유형 검증)이 애초에 스코프에서 빠졌는지 확인이 필요합니다.

### 2. market-data는 해소됨, risk-analysis에는 아직 같은 흔적이 남아있음

`market-data`는 실제로 구현되어 `property`에 연결되었고, `property`의 `marketComparison` 필드도 이제 조건에 따라 실제 시세 값을 채웁니다(더 이상 "항상 UNAVAILABLE 고정값"이 아님). 다만 `risk-analysis`는 아직 골격(엔티티/enum/정책설정/오케스트레이션 서비스) 단계이고, 신호 탐지 로직과 컨트롤러가 빈 스텁이라 실질적으로 동작하지 않습니다 — 이걸 미리 참조하려던 다른 도메인 코드엔 여전히 자리표시자만 남아있습니다.

- `property`의 매물 상세 응답엔 위험 신호·안전성 정보를 담을 필드 자체가 없음 (risk-analysis 쪽 컨트롤러가 없어 채울 데이터도 없음)
- `checklist`의 소유권취득일 문항에 "risk-analysis 전세가율과 연계"라는 주석만 있고 실제 연동 코드는 없음
- `risk-analysis` 내부의 `MarketDataClient`는 market-data 실 구현체(`MarketComparisonService`)가 이미 있음에도 그대로 연결할 수 없습니다 — 상태 모델(2단계 vs 3단계)과 메서드 시그니처가 달라, 임시로 항상 `UNDETERMINABLE`만 반환하는 `TemporaryMarketDataClient`를 꽂아둔 상태입니다. `property`가 예전에 쓰던 "항상 UNAVAILABLE" 고정값과 비슷한 임시방편이지만, 이번엔 데이터가 없어서가 아니라 **형태가 안 맞아서** 생긴 자리표시자라는 점이 다릅니다(`risk-analysis-design.md`의 'market-data 연동 격차' 참고).

셋 다 결국 risk-analysis 쪽의 미구현·형태 불일치가 원인이라, risk-analysis의 탐지 로직·어댑터 구현이 다음 우선 과제로 보입니다.

### 3. "매물 수정 시 재계산/무효화" 훅 — market-data는 해소, risk-analysis는 여전히 없음

요구사항 명세서 3곳(`property`, `market-data`, `risk-analysis`)이 공통으로 "가격/거래유형이 바뀌면 기존 시세·위험신호 결과를 무효화하고 재계산한다"를 요구했지만, 실제로는 두 도메인이 서로 다르게 해소됐습니다. `market-data`는 비교 결과를 아예 저장하지 않고 매물 조회/수정 때마다 즉시 재계산하므로(`MarketComparisonService.compare()`), "무효화할 저장된 상태" 자체가 없어 이 요구사항이 사실상 N/A가 되었습니다(`market-data-design.md` 비기능요구사항 참고). 반면 `risk-analysis`는 결과를 `PropertyRisk`/`PropertyRiskCheck`에 스냅샷으로 저장하는 구조라 재계산 트리거가 여전히 필요하고, `RiskRecalculationService`가 빈 스텁으로 자리만 마련된 채 `property` 수정(`PATCH /properties/{id}`)에도 훅이 없는 상태입니다. 나중에 risk-analysis를 구현할 팀원이 `property` 코드에도 훅을 추가해야 합니다.

### 4. 매물 삭제 이후 접근 차단이 도메인 진입점마다 다르게 적용됨

`property` 자체는 조회/수정 시 `isDeleted()`를 매번 재검사하지만, `checklist`는 **생성 시점에만** 매물 삭제 여부를 확인하고 조회·항목확인 시점엔 재검사하지 않습니다. 매물을 체크리스트 생성 후에 삭제해도 체크리스트는 계속 조회·수정할 수 있는 구멍이 있습니다. contract-analysis는 아직 매물 연동 자체가 TODO라 이 문제가 드러나지 않았을 뿐, `/analyze` 구현 시 같은 실수가 반복될 수 있습니다.

### 5. 이미지/파일 업로드 검증이 도메인마다 제각각

- `contract-analysis`: 형식(jpeg/png)·크기(10MB) 검증이 **실제로 구현되어 있음** (세 도메인 중 유일)
- `property`: 검증 로직 없음, 에러코드만 정의(위 1번 참고)
- `user`: `profileImageUrl`이 URL 문자열이라 애초에 업로드 자체가 없음(검증 대상이 없음)

같은 "이미지 업로드 검증"이라는 요구사항이 도메인마다 다르게 해석·구현되어 있어, 실제로 파일 업로드가 필요한 곳(property)엔 아직 없다는 점을 짚어둘 필요가 있습니다.

### 6. "존재하지 않음(404)" vs "권한 없음(403)" 구분 방식이 도메인마다 다름

- `property`: 404와 403을 명확히 구분해서 응답 — "권한 없는 사용자에게 존재 여부를 노출하지 않는다"는 비기능요구사항과 방향이 반대일 수 있음
- `checklist`: 소유자가 아니면 403이 아니라 그냥 404로 처리(조회 자체가 안 됨)
- `user`: "존재하지 않는 사용자"와 "탈퇴한 사용자"를 구분하지 않고 둘 다 404로 통일

세 방식 다 나름의 근거가 있지만, 팀 차원에서 "권한 없음은 403으로 드러낼지, 404로 숨길지"에 대한 공통 정책이 없다 보니 도메인마다 다르게 굳어진 것으로 보입니다.

### 7. 요구사항 명세서 자체의 내부 모순 (구현 문제가 아님)

`checklist` 요구사항의 "체크리스트 결과확인" 절은 본문(시스템 처리)에서 "시작 전이면 안내 메시지로 대체(성공)"라고 해놓고, 바로 아래 "실패 사유" 목록엔 "확인한 항목 없음"을 실패 케이스로 나열해 서로 모순됩니다. 실제 구현은 본문 설명(성공 처리)을 따랐습니다. 다른 도메인에서도 비슷한 명세서 내부 모순이 있을 수 있으니, 명세서를 다시 다듬을 때 참고할 사례로 남겨둡니다.

### 8. 더 이상 유효하지 않은 TODO

`contract-analysis`의 매물 소유권 검증 TODO는 "Property 엔티티/레포지토리 도입 후 구현"이라고 되어 있지만, `Property`는 이미 다른 도메인에 구현되어 있어 이 TODO는 더 이상 블로킹 사유가 아닙니다. 실제 연동만 하면 되는, 지금 당장 처리 가능한 작업입니다.

## 참고: 각 도메인 문서의 "남은 이슈" 개수

| 도메인 | 확인 필요 항목 수 |
|---|---|
| auth | 6개 |
| user | 8개 |
| property | 17개 |
| market-data | 4개 |
| checklist | 7개 |
| contract-analysis | 8개 |
| risk-analysis | 9개 |
