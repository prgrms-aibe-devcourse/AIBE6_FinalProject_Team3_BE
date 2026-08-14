# 도메인 전체 구현 현황 — 요약

## 배경

`auth-design.md`, `user-design.md`, `property-design.md`, `market-data-design.md`, `checklist-design.md`, `contract-analysis-design.md`, `risk-analysis-design.md` 7개 문서를 도메인별로 작성하면서 반복적으로 나타난 패턴을 모았습니다. 각 도메인 문서의 "확인 필요" 항목이 개별적으로는 별 것 아니어 보여도, 모아 놓고 보면 같은 원인에서 나온 경우가 많아 여기서 한 번에 짚습니다.

## 도메인별 구현 상태 한눈에

| 도메인 | 상태 | 비고 |
|---|---|---|
| `auth` | 거의 완전 구현 | **(2026-07-28 갱신)** 확인 필요 2개로 축소 — 다중 소셜 연동(`UserSocialAccount`)/jti 블랙리스트/토큰 실패 사유 구분 등 4개 해결. 남은 2개는 코드 문제가 아니라 카카오 email 심사 신청 여부(팀 결정)와 Refresh Token "별도 저장소 없음" 원문 의도(원작성자 확인) |
| `checklist` | 거의 완전 구현 | 요구사항 대비 확인 필요 8개 항목 전부 처리 완료(코드 5건 수정 + 문서화 3건 + 노션 명세서 반영). **(2026-08-06 추가)** `GET /checklists`(내 체크리스트 목록)에 DB 레벨 페이지네이션 추가 — 매물 목록(`GET /properties`)과 동일한 `PageResponse` 봉투 구조로 응답이 바뀜(기존 배열 응답에서 breaking change). **(2026-08-14 추가)** 실사용 피드백 반영 — 신규 문항 6개(단창/이중창·누전·차단기·보일러종류·냉난방방식·방범창), `ChecklistItemType.MULTIPLE_CHOICE` 신규 도입, 예시 이미지 스키마(`ChecklistItemTemplateImage`, `S3ImagePurpose.CHECKLIST_TEMPLATE`) 신설. 매물유형별 적용 문항 수 상한이 24개→30개로 늘어나 요구사항 범위 자체를 갱신함. **(2026-08-14 오후 추가)** 이미지 관리자 API(목록조회/추가/삭제) 구현 완료 — 재배포 없이 이미지 추가/삭제 가능(파일 업로드는 미지원, URL만 입력). 거래유형별 분기 미도입만 열려있는 사항으로 남음 |
| `user` | 거의 완전 구현 | **(2026-08-11 갱신)** 이미지 업로드(S3 presign/confirm/reset), 탈퇴 처리(익명화 + 연관 데이터 정리 + 세션 무효화, FE 연결까지 완료)가 모두 끝났습니다. 남은 건 "생성형 AI 사전고지"/`currentStage` 기반 위젯 우선순위가 FE 책임인지 확인하는 2건과, S3 이전 이미지 정리(태깅+즉시삭제)가 둘 다 실패하는 낮은 확률의 엣지케이스 1건뿐. **(2026-08-14 추가)** `user-design.md` 전수조사 결과에서 새로 발견된 이슈 2건도 처리됨 — `interestRegion` 길이 미검증(해결, `@Size(max=30)`) / 관리자 권한·상태 변경이 본인 탈퇴와 경쟁하는 TOCTOU(부분 해결, `AdminUserService`를 조건부 UPDATE로 교체 — 완전 봉쇄는 아직 아님). 자세한 내용은 `user-design.md`/`admin-design.md` 전수조사 결과 참고 |
| `property` | 부분 구현, 명세보다 크게 좁음 | 시세·위험신호·체크리스트 연동 전무. **(2026-08-06 정정)** "검색/페이지네이션 없음"은 더 이상 사실이 아님 — `GET /properties`가 지역/면적/가격 등 검색 조건 + `Pageable` 기반 페이지네이션(`PropertyRepository.search()`, `PageResponse`)을 이미 지원함 |
| `contract-analysis` | ~~부분 구현(진행 중) — 핵심 단계인 AI 분석(`/analyze`) 자체가 없음~~ | **(2026-08-12 정정)** 이미 사실이 아님 — `/analyze`는 `GeminiClientImpl` 연동, 환각 검증, `aiGeneratedNotice`/`disclaimer` 필드까지 구현 완료된 상태(`contract-analysis-design.md` 전수조사 결과 참고). 남은 건 그 문서의 "남은 이슈" 목록 참고 |
| `market-data` | 거의 완전 구현 | 반경 기반 실거래가 비교 로직 동작, `property`에 실제 연결됨. ~~남은 건 FE 연동, 실시간재계산→캐싱 전환 여부 등 4개~~ **(2026-08-12 정정)** FE 연동은 이미 완료됨(`market-data-design.md` 전수조사 결과 참고) — 남은 건 실시간재계산→캐싱 전환 여부 등 나머지 항목 |
| `risk-analysis` | 완전 구현 | **(2026-08-04 완료)** 신호 탐지기 4종, API 4개(`POST /risk-analysis`, `GET /risk-signals`, `GET /deposit-safety`, `POST /deposit-safety/recalculate`), market-data 어댑터(전세+매매), `DepositSafetyCheckService`(전세가율), checklist 연계 보조 신호, 매물 수정 시 자동 재계산 트리거(이벤트 기반)까지 전부 구현 완료. ~~완전 미해결 이슈 없음 — 남은 건 "동일 계정 다수 등록" 활성화 여부·선순위보증금 입력 화면 배치 등 팀/FE 결정 2건뿐~~ — **(2026-08-12 정정)** 요구사항 기능 자체는 전부 구현됐지만, 같은 날 진행된 코드 전수조사에서 새로운 버그/코드품질 이슈 5건이 발견됨(트랜잭션 경계 불일치, 판정불가 사유 오분류, 죽은 설정값, 역방향 의존, `@Version` 부재) — `risk-analysis-design.md` 전수조사 결과 섹션 참고, 팀/FE 결정 대기 2건과는 별개 |

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
| user | 3개 (2026-08-11 갱신, 8개 중 5개 해결 — 탈퇴 관련 4건 + 프로필 등록 409 정정 1건. 남은 3개는 FE/BE 책임 소재 확인 2건 + S3 이중실패 엣지케이스 1건 — `user-design.md` 참고). **(2026-08-14 추가)** 이 개수는 여전히 유효하나, 같은 문서의 전수조사 결과 섹션에서 별도로 발견된 이슈(`interestRegion` 길이 검증 미비, 권한/상태변경-탈퇴 TOCTOU)는 이번에 해결/부분 해결됨 |
| property | 17개 |
| market-data | 4개 |
| checklist | 1개 (8개 중 7개 처리 완료, **(2026-08-14 추가)** 이미지 관리자 API 부재도 같은 날 오후 해결. 남은 건 거래유형별 분기 여부뿐 — `checklist-design.md` 참고) |
| contract-analysis | 8개 |
| risk-analysis | ~~0개 (2026-08-04 완료, 원본 9개 전부 해결 + 신규 발견 1개도 해결. 남은 건 팀/FE 결정 대기 2건뿐)~~ — **(2026-08-12 정정)** 이 개수는 요구사항 대비 "확인 필요" 항목 기준이라 여전히 유효하나, 같은 날 진행된 코드 전수조사(품질/버그 관점)에서 별도로 5건이 새로 발견됨 — `risk-analysis-design.md` 전수조사 결과 섹션 참고 |

## 공통/인프라 코드 전수조사 결과 (2026-08-12)

이 절은 특정 도메인 패키지에 속하지 않는 공통 코드(backend `global` 패키지 + 설정 파일, frontend `ui`/`config`/`data`/`types`/`mocks`)를 대상으로 한다. 개별 도메인 코드는 각 `{도메인}-design.md`의 "전수조사 결과" 섹션을 참고. Frontend 쪽 발견은 `frontend/docs/specs/cross-domain-summary.md`에 별도로 정리했다.

### 버그/정확성

1. 특별히 발견된 이슈 없음. `ApiResponse`/`ApiError`/`PageResponse`/`PageableUtils`/`GlobalExceptionHandler`는 전부 일관되고 방어적으로 구현되어 있다 — `GlobalExceptionHandler`의 catch-all(`Exception.class`)은 스택트레이스나 예외 메시지를 응답 바디에 흘리지 않고 로그로만 남기며(`GlobalExceptionHandler.java:132-136`), `NoResourceFoundException`을 별도 핸들러로 분리해 매핑 안 된 경로 요청이 500(`INTERNAL_SERVER_ERROR`)이 아니라 404로 정확히 내려가게 해뒀다(`GlobalExceptionHandler.java:127-130`, 주석에 그 이유까지 명시). `ChecklistService.listMyChecklists`가 다른 목록 API(`PropertyService`, `AdminUserService`, `AdminPropertyReportService`)와 달리 `PageableUtils.validateSort`를 호출하지 않는 것도 버그가 아니라 "정렬을 고정하고 클라이언트가 보낸 sort는 의도적으로 버린다"는 문서화된 설계다(`ChecklistService.java:152-158`).

### 보안

1. **`S3PresignService`를 공유하는 `user`(프로필 이미지)와 `property`(매물 이미지) 사이에 confirm 단계의 소유권 검증이 비대칭적이다.** `UserService.confirmProfileImageUpload()`는 `confirmUpload()`를 호출하기 전에 반드시 `S3KeyGenerator.isProfileImageOwnedBy(userId, key)`로 그 key가 실제로 이 사용자의 `profile-images/{userId}/` 네임스페이스인지 확인한다(`UserService.java:128-135`). 반면 `PropertyImageUploadController.confirm()`(`backend/src/main/java/com/algogyeyak/property/controller/PropertyImageUploadController.java:53-61`)은 `@AuthenticationPrincipal`조차 받지 않고, `PropertyImageConfirmRequest.key()`를 그대로 `s3PresignService.confirmUpload(request.key(), S3ImagePurpose.PROPERTY)`에 넘긴다 — 이에 대응하는 `S3KeyGenerator.isPropertyImageOwnedBy(...)` 같은 검증 자체가 존재하지 않는다. `SecurityConfig`상 `/properties/images/**`는 `permitAll` 목록에 없어 `anyRequest().authenticated()`로 떨어지므로 "로그인은 필요"하지만, 로그인한 아무 사용자나 이 엔드포인트에 **임의의 S3 key 문자열**을 넘길 수 있다는 점은 그대로다. 게다가 `S3PresignService.confirmUpload()`/`generateDownloadUrl()`은 `purpose` 인자(호출부가 정한다)의 컨텐츠타입·용량·공개여부 정책만 검사할 뿐, `key`가 실제로 그 `purpose`의 프리픽스(`property-images/`)에 속하는지는 전혀 확인하지 않는다(`S3PresignService.java:90-103`, `:134-150`). 그 결과:
   - 로그인한 사용자 B가 다른 사용자 A의 아직 confirm 안 된 `property-images/{A}/...` key를 알아내면(추측이 어렵진 해도 UUID라 쉽진 않음) `/properties/images/confirm`에 그대로 넘겨 PENDING 태그를 지우고 "확정" URL을 받아갈 수 있다 — 원래 A가 해야 할 confirm을 B가 가로채는 셈이고, 그 사이 A의 정상 흐름이 깨진다.
   - 더 심각한 경우: 같은 요청에 `contract-images/{피해자}/...` key(원래 `S3ImagePurpose.CONTRACT` — 비공개, presigned GET으로만 조회)를 `S3ImagePurpose.PROPERTY`로 넘길 수 있다. 실제 S3 객체의 content-type/size가 PROPERTY 기준(jpg/png/webp/gif, ≤10MB)도 만족하면(CONTRACT 허용 범위의 부분집합이라 보통 만족한다) `confirmUpload`가 그대로 통과해 (a) 계약서 이미지의 `PENDING_UPLOAD_TAG`를 지워 OCR 후 자동 정리(Lifecycle)를 무력화하고, (b) `generateDownloadUrl(key, S3ImagePurpose.PROPERTY)`가 이 key를 "공개 대상"으로 취급해 서명 없는 고정 URL을 만들어 돌려준다. 실제로 그 URL이 열리는지는 S3 버킷 정책이 프리픽스별로 엄격히 나뉘어 있는지에 달려 있어(코드 밖의 설정) 코드만으로 최종 결론을 낼 수는 없지만, 코드 계층 자체가 "이 key가 이 purpose에 속하는지"를 전혀 검증하지 않는다는 설계상 공백은 명확하다.
   - 수정 방향: `S3KeyGenerator`에 `isPropertyImageOwnedBy(Long userId, String key)`를 `isProfileImageOwnedBy`와 동일한 패턴으로 추가하고, `PropertyImageUploadController.confirm()`이 `@AuthenticationPrincipal`을 받아 이 검증을 통과한 key만 confirm하도록 고친다(`FILE_KEY_ACCESS_DENIED` 재사용). 더 근본적으로는 `S3ImagePurpose`에 프리픽스 상수를 추가해 `S3PresignService.confirmUpload`/`generateDownloadUrl` 자체가 "key가 purpose의 프리픽스로 시작하는지"를 공통으로 검증하게 하면, 개별 컨트롤러가 매번 이 검증을 기억해서 넣어야 하는 지금 구조의 재발 위험을 없앨 수 있다.

**(2026-08-12 부분 해결)** 공통 코드 쪽(이 절이 제안한 두 방향 중 두 번째)을 적용했다:
- `S3ImagePurpose`에 `prefix()`를 추가해(PROFILE=`profile-images/`, PROPERTY=`property-images/`, CONTRACT=`contract-images/`) `S3KeyGenerator`가 이 값을 그대로 참조하도록 정리(하드코딩된 prefix 문자열 제거)
- `S3PresignService.confirmUpload()`/`generateDownloadUrl()`에 `validatePurposePrefix(key, purpose)`를 추가해, key가 그 purpose의 prefix로 시작하지 않으면 `FILE_KEY_ACCESS_DENIED`(403)로 즉시 거부하도록 함 — 위 목록의 "**더 심각한 경우**"(`contract-images/`를 `PROPERTY` purpose로 넘기는 교차 도메인 오용)는 이걸로 막힘
- `S3KeyGenerator.isPropertyImageOwnedBy(Long userId, String key)`도 `isProfileImageOwnedBy`와 동일한 패턴으로 추가했지만, 이 시점엔 아직 아무도 호출하지 않았다 — `PropertyImageUploadController`는 이번 작업 범위(공통 코드만) 밖이라 의도적으로 건드리지 않음.
- ~~**여전히 남은 문제**: 위 목록의 "로그인한 사용자 B가 다른 사용자 A의 pending property-images/{A}/... key를 confirm" 케이스(같은 purpose, 다른 소유자)는 이번 수정으로 막히지 않는다 ... `PropertyImageUploadController.confirm()`이 `@AuthenticationPrincipal` + 위에서 준비해둔 `isPropertyImageOwnedBy()`를 호출하도록 고치는 후속 작업이 property 담당자에게 필요하다.~~ — ✅ **(2026-08-13 해결 확인)** property 담당자가 `dev`에 독립적으로 같은 방향의 수정을 이미 올려뒀다 — `PropertyImageUploadController.confirm()`이 `@AuthenticationPrincipal`을 받아 `S3KeyGenerator.isPropertyImageOwnedBy(principal.userId(), request.key())`를 호출하고 실패 시 `FILE_KEY_ACCESS_DENIED`를 던지도록 연결됨(property 팀이 자체적으로 `isPropertyImageOwnedBy`도 별도 구현). `origin/dev`를 이 브랜치에 머지하면서 발견 — `S3KeyGenerator.java`는 property 팀 버전을 그대로 채택했고(사소한 구현 차이만 있고 로직은 동일), 이 공통 코드의 `S3PresignService.validatePurposePrefix()`(교차 도메인 오용 방지)와 함께 적용되어 이제 같은 purpose·다른 소유자 케이스와 교차 도메인 오용 케이스 둘 다 막혀 있다. 관련 테스트(`PropertyImageUploadControllerTest` 6개) 재실행으로 회귀 없음 확인.

2. **prod 프로필의 "기본값 제거로 fail-fast" 정책이 인증 관련 시크릿에만 적용되고, 나머지 외부 연동 시크릿엔 빠져 있다.** `application-prod.yml`은 `JWT_SECRET`/`OAUTH2_STATE_SIGNING_KEY`/`CORS_ALLOWED_ORIGINS`/`COOKIE_SAME_SITE`/`DEV_LOGIN_SECRET`에 대해 `application.yml`의 더미 기본값을 명시적으로 제거해, 배포 시 해당 환경변수를 빠뜨리면 기동 자체가 실패하도록(`PlaceholderResolutionException`) 강제한다(주석에도 그 의도가 상세히 적혀 있음). 그런데 `application.yml`의 `aws.s3.access-key`/`aws.s3.secret-key`(113-118행, 기본값 `dummy-access-key-for-local`/`dummy-secret-key-for-local`)와 `kakao.rest-api-key`(69행), `clova.ocr.secret-key`(74행), `gemini.api-key`(77행), `molit.service-key`(83행)는 `application-prod.yml`에 대응하는 override가 전혀 없어, 더미 기본값이 prod에도 그대로 살아있다. 실제 배포에서 해당 환경변수(특히 `AWS_ACCESS_KEY`/`AWS_SECRET_KEY`)를 깜빡해도 앱은 "정상 기동"한 뒤 S3/Clova/Gemini/국토부 API 호출이 조용히 실패(또는 인증 오류)하는 상태가 된다 — 팀이 이미 다른 시크릿엔 적용한 "빠뜨리면 바로 알 수 있게 실패시킨다"는 원칙과 일관되지 않는다. 데이터 유출로 이어지는 건 아니지만(자격증명 자체가 새는 게 아니라 기능이 조용히 깨지는 쪽), 이번 리뷰에서 짚은 S3 presign 흐름의 신뢰 경계를 생각하면 최소한 `aws.s3.access-key`/`secret-key`만큼은 같은 fail-fast 패턴으로 맞추는 게 안전하다.
   파일: `backend/src/main/resources/application.yml:69,74,77,83,113-118`, `backend/src/main/resources/application-prod.yml`(대응 override 없음)

   ✅ **(2026-08-12 해결)** `application-prod.yml`에 `kakao.rest-api-key`/`clova.ocr.secret-key`/`gemini.api-key`/`molit.service-key`/`aws.s3.access-key`/`aws.s3.secret-key` 6개 전부 기본값 없는 override를 추가해, 배포 시 해당 환경변수를 빠뜨리면 다른 시크릿들과 동일하게 기동 자체가 실패하도록 맞췄다. `clova.ocr.invoke-url`은 시크릿이 아니라 URL 설정값이라 이번 범위에서 제외(더미 기본값 유지).

### 코드 품질 (중복/구조/일관성)

1. ~~"presign으로 발급받은 key가 실제로 이 사용자/이 용도의 것인지 confirm 시점에 검증한다"는 정책이 코드 어디에도 공통 유틸로 뽑혀 있지 않고, `user` 도메인의 `UserService`/`S3KeyGenerator.isProfileImageOwnedBy`에만 존재한다(위 보안 1번 참고). `S3ImagePurpose`가 이미 "여러 클래스에 각각 상수를 두면 나중에 한쪽만 바뀌어 어긋나는 걸 막기 위해" 정책을 한 곳에 모은 전례가 있으므로(`S3ImagePurpose.java` 상단 주석), 소유권 검증도 같은 정신으로 `S3KeyGenerator`/`S3PresignService` 레벨의 공통 로직으로 승격하는 게 이 코드베이스의 기존 설계 의도와도 맞다.~~ — ✅ **(2026-08-13 해결)** "key가 이 *purpose*의 것인지"(prefix 검증)는 `S3PresignService.confirmUpload()`/`generateDownloadUrl()` 레벨로 승격해 모든 호출부에 공통 적용됨(위 보안 1번 참고). "key가 이 *사용자*의 것인지"(per-user 소유권)는 구조적으로 `S3PresignService` 레벨로는 승격 불가능(호출자의 userId를 아는 건 컨트롤러 레벨뿐)하지만, 이제 `user`(`UserService.confirmProfileImageUpload()`)와 `property`(`PropertyImageUploadController.confirm()`, property 팀이 `dev`에 독립적으로 반영) 두 도메인 모두 각자의 컨트롤러/서비스 레벨에서 실제로 이 검증을 호출하도록 연결까지 끝났다 — "정책만 준비되고 강제 안 됨" 상태에서 "두 도메인 다 강제 적용됨"으로 해소.

## 배포/인프라 설정 전수조사 결과 (2026-08-12)

이 절은 Dockerfile/docker-compose/CI workflow/Gradle 빌드 설정 등 배포·빌드 인프라 파일을 대상으로 한다.

### 보안

1. ~~**Redis/DB/Grafana 시크릿이 docker-compose 레벨에서는 "필수값"으로 강제되지 않는다.** `docker-compose.yml`의 redis 서비스는 `--requirepass ${REDIS_PASSWORD}`(`docker-compose.yml:9`)로 비밀번호를 받고, backend 서비스는 `SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}`(`:35`)·`SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}`(`:39`)를 그대로 `${VAR}` 치환으로 쓴다. `docker-compose.monitoring.yml`의 `GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}`(`:21`)도 같은 패턴이다. 전부 `${VAR:?message}` 같은 필수값 강제 문법이 아니라서, EC2 서버의 `.env`에 해당 변수가 빠져 있어도 Compose는 에러 없이 빈 문자열로 조용히 치환하고 넘어간다 — Redis라면 `--requirepass ''`로 인증 없는 Redis가 뜰 수 있고, Grafana라면 관리자 비밀번호가 빈 값이 될 수 있다.~~ — ✅ **(2026-08-12 해결)** `docker-compose.yml`의 `REDIS_PASSWORD`(2곳: `--requirepass`, healthcheck)·`DB_PASSWORD`, `docker-compose.monitoring.yml`의 `GRAFANA_ADMIN_PASSWORD` 전부 `${VAR:?메시지}` 문법으로 바꿔, 값이 없으면 `docker compose up` 자체가 에러 메시지와 함께 실패하도록 맞췄다.

2. **백엔드 포트는 loopback으로 좁혀뒀지만, 같은 파일의 리버스프록시 관리 콘솔 포트는 그렇지 않다.** `docker-compose.prod.yml:6`은 backend 포트를 `127.0.0.1:8080:8080`으로 묶어 외부에서 직접 접근을 막았는데, 같은 파일의 `nginx-proxy-manager` 서비스는 `80`/`443`/`81`(`:12-14`) 전부를 호스트의 모든 인터페이스(`0.0.0.0`)에 노출한다. 포트 81은 NPM의 관리 UI로, 뚫리면 리버스프록시 설정 전체(TLS 인증서, 라우팅 규칙 등)를 탈취당할 수 있는 민감한 공격 표면인데 별다른 제한이 없다. AWS 보안그룹에서 81번을 막아뒀다면 실제 위험은 없지만, 이 파일만 보면 그 전제가 드러나지 않고, backend 포트에 대해서는 신경 써서 좁혀둔 것과 대비된다.

3. ~~`backend/gradle/wrapper/gradle-wrapper.properties`에 `distributionSha256Sum`이 없다. `validateDistributionUrl=true`는 있어 다운로드 URL 자체(허용된 호스트인지)는 검증하지만, 다운로드된 Gradle 배포판 zip의 체크섬은 검증하지 않는다 — Gradle 공식 문서가 wrapper 무결성 강화용으로 제공하는 항목 중 하나가 비어있는 상태다. `gradlew`/`gradlew.bat`은 라이선스 헤더 등을 봤을 때 표준 Gradle wrapper 스크립트 그대로이고 임의 수정 흔적은 없다.~~ — ✅ **(2026-08-12 해결)** `gradle-wrapper.properties`에 `distributionSha256Sum=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f`을 추가했다. 이 값은 Gradle 공식 사이드카(`gradle-9.5.1-bin.zip.sha256`)를 가져온 것에 더해, 실제 배포판 zip을 직접 내려받아 로컬에서 `sha256sum`으로 재계산한 값과도 일치함을 확인한 뒤 반영했다(사이드카 값만 신뢰하지 않고 이중 검증). 반영 후 `./gradlew.bat --version`으로 wrapper가 체크섬 검증을 통과하고 정상 동작하는 것도 확인했다.

4. `.env.example`에는 실제 운영값처럼 보이는 항목이 없다. `JWT_SECRET`/`OAUTH2_STATE_SIGNING_KEY`/`DEV_LOGIN_SECRET`에 채워진 값은 이름 자체가 `local-dev-only-...`/`local-dev-secret`로 플레이스홀더임을 명시하고 있고, 나머지(AWS/DB/Kakao/Clova/Gemini/MOLIT/Grafana 관련 변수)는 전부 빈 값이다.

### 버그/정확성

1. ~~**`algogyeyak-network`를 `external: true`로 선언해둔 것이, 이 네트워크를 처음 만드는 경로를 저장소 어디에도 남기지 않는다.** `docker-compose.prod.yml:26-28`은 `algogyeyak-network`를 `external: true`로 선언한다. 이 파일은 배포 워크플로우(`deploy.yml:39`)에서 `docker-compose.yml`(네트워크를 `driver: bridge`로 직접 생성, `:46-48`)과 항상 `-f`로 함께 합쳐지는데, Compose가 여러 파일에 걸친 같은 네트워크 키를 병합할 때 `external: true`가 있으면 그 네트워크는 "이미 존재해야 하는 것"으로 취급되어 Compose가 직접 생성하지 않는다. 즉 이 조합으로 `docker compose up`을 처음 실행하는 시점(EC2 인스턴스를 새로 구성하는 재해복구 상황 등)에는 누군가 미리 `docker network create algogyeyak-network`를 수동으로 실행해두지 않으면 "network not found" 오류로 실패한다. 저장소 안에는 이 사전 단계를 수행하는 스크립트나 문서가 없다.~~ — ✅ **(2026-08-12 해결)** `deploy.yml`의 배포 스크립트에 `docker network inspect algogyeyak-network || docker network create algogyeyak-network`(없을 때만 생성, 이미 있으면 아무 것도 안 함)를 `docker compose up` 직전에 추가해 재해복구 시나리오에서도 자동으로 생성되도록 함 — 기존에 이미 떠 있는 운영 네트워크는 그대로 유지되므로 현재 운영 환경엔 영향 없음.

2. 이 외 Dockerfile/compose/CI 워크플로우 로직 자체에서는 문제를 발견하지 못했다. `docker-compose.override.yml`이 backend 서비스에 `profiles: [full-container]`를 추가해(`:6-8`) 기본 `docker compose up`으로는 backend가 뜨지 않게 만드는 것도, `CURRENT_STATE.md`가 문서화한 로컬 워크플로우(Redis만 컨테이너로 띄우고 Spring Boot는 IDE/Gradle로 직접 실행)와 일치하는 의도된 설계다. `deploy.yml`이 명시적으로 `-f` 3개(`docker-compose.yml`/`docker-compose.prod.yml`/`docker-compose.monitoring.yml`)만 지정해 `docker-compose.override.yml`/`docker-compose.monitoring.override.yml`을 prod 배포에서 배제하는 것도 올바르다. Dockerfile의 `HEALTHCHECK`/멀티스테이지 빌드/non-root `USER app` 전환도 이상 없다.

### 코드 품질

1. ~~**`.env.example`에 `KAKAO_REST_API_KEY`와 `MOLIT_SERVICE_KEY`가 두 번씩 선언돼 있다** — 카카오 로컬 API 설명 주석 블록(`.env.example:39`, `:43`)에 한 번씩 정의된 뒤, 파일 뒤쪽 AWS/DB 섹션 바로 앞(`:55-56`)에 주석 없이 다시 나열되어 있다.~~ — ✅ **(2026-08-12 해결)** 뒤쪽에 중복 선언돼 있던 두 줄을 제거, 각 키는 원래 위치(주석 블록과 함께)에만 남김.

2. ~~**`docker-compose.monitoring.override.yml`은 이름은 `docker-compose.override.yml`과 비슷하지만 자동으로 병합되지 않는다.**~~ — ✅ **(2026-08-12 해결)** 파일 상단에 "정확히 `-f docker-compose.monitoring.yml -f docker-compose.monitoring.override.yml`을 둘 다 명시해야 한다"는 안내 주석 추가.

## 테스트 코드 품질 전수조사 결과 (2026-08-12)

이 절은 테스트 코드 자체의 품질(약한 assertion, 커버리지 공백, 과도한 mock 의존 등)을 대상으로 한다 — 애플리케이션 코드 자체의 결함은 각 도메인 spec 문서의 "전수조사 결과" 섹션 참고. `backend/src/test/java/com/algogyeyak/**` 76개 테스트 파일 전체를 목록화한 뒤, 위 문서들의 2026-08-12 "전수조사 결과"에서 새로 발견된 버그/보안 이슈마다 대응하는 테스트가 실제로 그 경로를 검증하는지 하나씩 대조했다.

### 커버리지 공백 (놓친 실제 버그와의 연관)

1. **contract-analysis 이미지 크기 제한(1MB~10MB에서 400 대신 500)을 어떤 테스트도 발견할 수 없는 구조다.** `ContractAnalysisOcrServiceTest.java`(`backend/src/test/java/com/algogyeyak/contractanalysis/service/ContractAnalysisOcrServiceTest.java`)와 `ContractAnalysisInputService`용 테스트는 전부 `mock(MultipartFile.class)`를 만들어 서비스 메서드를 직접 호출한다(예: `jpegImage()` 헬퍼, L24-30) — 즉 실제 HTTP 멀티파트 파싱(`DispatcherServlet`/`StandardMultipartResolver`)을 전혀 거치지 않는다. 이 버그는 정확히 "멀티파트 리졸버가 1MB 초과 시 서비스 코드에 도달하기 전에 `MaxUploadSizeExceededException`을 던진다"는 것이므로, 서비스 레벨 단위 테스트로는 원천적으로 재현할 수 없다. `@SpringBootTest`+`MockMvc`(또는 `@WebMvcTest`)로 실제 멀티파트 요청을 보내는 통합 테스트가 이 두 서비스 어디에도 없다. 같은 맥락에서 `GlobalExceptionHandlerTest.java`(`backend/src/test/java/com/algogyeyak/global/exception/GlobalExceptionHandlerTest.java`)는 `handleBusinessException`/`handleNoResourceFoundException`/`handleException(RuntimeException)` 3개 핸들러만 테스트하고, `MaxUploadSizeExceededException` 전용 케이스가 없다 — 실제로 그런 핸들러 자체가 없기 때문인데, 이 부재를 지적하는 테스트도 없다.
2. **contract-analysis `/analyze`의 propertyId 소유권 우회를 어떤 테스트도 건드리지 않는다.** `ContractAnalysisAnalyzeServiceTest.java`의 `analyze()` 헬퍼(L27-29)는 `new ContractAnalysisAnalyzeRequest(maskedText, userConfirmed, null)`로 `propertyId`를 항상 `null`로 고정해서 호출한다. 29개 테스트 메서드 전부가 이 헬퍼를 거치므로, `propertyId`에 실제 값(예: 다른 사용자 소유 매물의 id)을 채워 넘겼을 때 무슨 일이 일어나는지(현재는 아무 검증도 없이 그대로 통과) 검증하는 테스트가 하나도 없다. "죽은 필드라 안전하다"가 아니라 "이 필드가 죽어 있다는 사실 자체를 아무 테스트도 실패로 드러내지 않는다"는 게 문제 — 나중에 누군가 이 필드를 읽어 소유권 검증을 붙이려 할 때 회귀 여부를 판단할 기준 테스트조차 없다.
3. **market-data `referencePrice == 0` → `Infinity`/`NaN` 경로가 테스트되지 않는다.** `MarketComparisonServiceTest.java`(`backend/src/test/java/com/algogyeyak/marketdata/service/MarketComparisonServiceTest.java`)는 반경/표본수/면적오차/월세제외/지오코딩실패 등 판정 분기는 폭넓게 다루지만(L116-344), 표본 전부가 `depositWon <= 0`이어서 median이 0이 되는 경우를 만드는 테스트가 없다. 이 서비스가 `@Cacheable`이라 응답이 그대로 캐시에 박히고 Jackson이 `Infinity`/`NaN`을 직렬화하다 실패할 수 있다는, 실제 사용자에게 500으로 보일 수 있는 경로인데도 커버리지가 없다.
4. **property 이미지 confirm의 소유권 미검증(IDOR)을 정확히 놓치는 방식으로 테스트가 짜여 있다.** `PropertyImageUploadControllerTest.java`(`backend/src/test/java/com/algogyeyak/property/controller/PropertyImageUploadControllerTest.java`)의 `업로드_확인에_성공하면_200과_imageUrl을_반환한다()`(L90-105)는 `key = "property-images/1/abc.jpg"`와 `USER_ID = 1L`을 나란히 써서, 우연히 "요청자 = 키 소유자"인 경우만 검증한다. 이 컨트롤러가 `@AuthenticationPrincipal`조차 받지 않는다는 사실(공통 문서 "S3PresignService를 공유하는 user와 property 사이 비대칭" 참고)을 감안하면, `asUser(2L)`로 다른 사용자를 인증시키고 동일한 `property-images/1/...` key로 confirm을 호출해 200이 그대로 나오는지(현재 코드는 그대로 통과시킨다) 확인하는 네거티브 테스트가 있어야 이 버그가 테스트만으로도 드러났을 것이다. 대응하는 `UserService`(프로필 이미지) 쪽에는 `isProfileImageOwnedBy` 검증이 있어 자연스레 이런 케이스를 막지만, property 쪽 테스트는 애초에 그 시나리오를 시도조차 하지 않는다.
5. **property 등록/수정 시 이미지 URL이 임의의 외부 도메인이어도 통과한다는 사실이 테스트에 그대로 "정상 동작"처럼 박혀 있다.** `PropertyServiceTest.java`(`backend/src/test/java/com/algogyeyak/property/service/PropertyServiceTest.java`)의 성공 케이스들(L114-354 부근)은 전부 `https://cdn.algogyeyak.com/img/....jpg`를 이미지 URL로 사용하는데, 이 문자열은 실제 S3 presign/confirm 흐름을 거친 값이 아니라 테스트가 임의로 지어낸 도메인이다. `validateImages()`가 프로토콜/확장자/개수만 확인하고 발급 경로를 확인하지 않는다는 문서의 발견과 정확히 같은 성격 — `https://attacker.example.com/x.jpg`로도 이 테스트들이 검증하는 것과 동일하게 통과한다는 걸 보여주는 (음성) 테스트가 없어서, "임의 외부 URL이 그대로 저장된다"는 위험이 테스트 스위트 안에서는 전혀 드러나지 않는다.
6. **property 지역 검색(`region`)의 LIKE 와일드카드 미이스케이프가 어느 레벨에서도 테스트되지 않는다.** `PropertyServiceTest.java`의 `지역_검색어로_필터링하면_repository_search에_region이_전달된다()`(L618-633)는 `propertyRepository`를 `@Mock`으로 완전히 대체해 "region 문자열이 그대로 인자로 전달되는지"만 확인한다 — 실제 `@Query` JPQL의 `LIKE CONCAT('%', :region, '%')`는 실행되지 않는다. 실제 리포지토리를 쓰는 `PropertyRepositoryTest.java`(`backend/src/test/java/com/algogyeyak/property/repository/PropertyRepositoryTest.java`)는 `countDistinctUserIdIn`류 통계 쿼리만 다루고 `search()`/`region` 관련 테스트가 아예 없다. 즉 "검색어에 `%`나 `_`를 넣으면 와일드카드로 해석된다"는 버그를 잡을 수 있는 테스트가 서비스 레벨(mock이라 못 잡음)에도 리포지토리 레벨(테스트 자체가 없음)에도 존재하지 않는다.
7. **risk-analysis `checkAndSave(Property)`의 트랜잭션 경계 문제(감지기 REQUIRES_NEW 결과와 `depositSafetyCheckService` 호출 실패 시 불일치)를 재현하는 테스트가 없다.** `FakeListingSignalServiceTest.java`(`backend/src/test/java/com/algogyeyak/riskanalysis/service/FakeListingSignalServiceTest.java`)는 `depositSafetyCheckService`를 매 테스트마다 정상 동작하는 mock으로만 사용한다(L44, L134-141의 `checkAndSaveAlsoRunsDepositSafetyCheck` 등) — `when(depositSafetyCheckService.checkAndSave(any())).thenThrow(...)`처럼 예외를 던지도록 스텁해서 "신호 4종 upsert는 이미 커밋됐는데 보증금 계산만 실패한다"는 정확히 문서가 지적한 상황을 만드는 테스트가 없다. `RiskRecalculationService`의 예외 흡수 동작은 `RiskRecalculationServiceTest.java`에서 별도로 테스트되는 것과 대조적으로, `checkAndSave(Property)` 안의 미보호 호출은 정상 경로만 반복해서 검증된다.
8. ~~**checklist 관리자 템플릿 버전 산정이 "비활성 문항에 더 높은 버전이 남아있는" 경우를 테스트하지 않는다.** `AdminChecklistTemplateServiceTest.java`의 `template()` 헬퍼(L44-56)는 `.active(true)`를 무조건 고정해서 만든다. 정작 문서가 지적한 위험 시나리오(비활성 템플릿 중 하나가 활성 템플릿들보다 더 높은 버전을 가진 경우 새 문항이 실제 활성 배치보다 앞서가는 버전을 받는 문제)를 재현하는 테스트는 없다.~~ — ✅ **(2026-08-12 해결)** 근본 원인 자체를 고쳤다 — `AdminChecklistTemplateService.create()`의 버전 산정을 `findAllByOrderByDisplayOrderAsc()`(전체)에서 `findByActiveTrueOrderByDisplayOrderAsc()`(활성만)로 변경해, 비활성 문항의 높은 버전이 더 이상 새 문항에 영향을 주지 않도록 했다. `template()` 헬퍼에 `active` 파라미터를 받는 오버로드를 추가하고, 이 시나리오(비활성 문항 version=5, 활성 문항 version=2인 상태에서 새 문항이 2를 물려받는지)를 직접 재현하는 회귀 테스트 `createIgnoresHigherVersionFromInactiveTemplates()`를 신설함.
9. ~~**auth `LocalAuthService.login()`의 SUSPENDED 계정 처리(계정 없음과 동일하게 뭉뚱그리는 부분)가 이 도메인 테스트에서 전혀 검증되지 않는다.** `LocalAuthServiceTest.java` 전체를 봐도 "suspend"/"SUSPENDED"를 다루는 테스트 메서드가 없다.~~ — ✅ **(2026-08-12 해결)** `loginThrowsForSuspendedAccountWithoutRevealingItExists()` 추가 — 정지 계정이 `AUTH_INVALID_CREDENTIALS`(계정 없음과 동일)로 응답하는지 명시적으로 고정(pin)함.

### 약한 assertion / 부실한 검증

특별히 심각한 사례는 찾지 못했다. `isNotNull()`만으로 끝나는 assertion 3곳(`AdminAuditLoggerIntegrationTest.java:59`의 `createdAt`, `GlobalExceptionHandlerTest.java:23/37/47`의 `response.getBody()`, `FakeListingSignalServiceTest.java:275`의 `calculatedAt()`)을 확인했으나, 전부 그 다음 줄에서 실제 값(코드/메시지/카운트 등)을 구체적으로 검증하는 assertion이 이어지거나(`GlobalExceptionHandlerTest`), 타임스탬프처럼 정확한 값을 assert하기 어려운 필드라 `isNotNull()`이 합리적인 선택인 경우였다. `assertDoesNotThrow`는 저장소 전체에 딱 1곳(`CustomOAuth2UserServiceLazyLoadingIntegrationTest.java:75`)뿐이고, 그 반환값(`getAuthorities()`)을 곧바로 이어서 구체적으로 검증하므로 "예외만 안 나면 통과"류의 부실한 패턴은 아니다.

### 기타 (비활성화된 테스트, 과도한 mock 의존, 플레이키 가능성)

1. **저장소 전체에서 `@Disabled`는 정확히 1곳뿐이고, 사유가 상세히 문서화되어 있다.** `ContractAnalysisMaskingServiceTest.doesNotMaskWhenLabelIsFollowedBySpaceOnlyThenCommonNoun()`(`backend/src/test/java/com/algogyeyak/contractanalysis/service/ContractAnalysisMaskingServiceTest.java:213-226`)는 "임차인 계약 기간 중"처럼 라벨 뒤에 조사/구분자 없이 공백만 오는 경우, 정규식만으로는 실제 이름과 일반 명사를 구분할 수 없다는 known limitation을 `@Disabled` 사유 문자열에 상세히 남기고, 사용자 확인(userConfirmed) 단계로 보완한다는 근거까지 적어뒀다. 문제로 지적할 수준은 아니고, 오히려 "왜 꺼져 있는지"를 잘 남긴 사례다.
2. **`FakeListingSignalServiceTest`류가 `depositSafetyCheckService`/`marketDataClient`를 항상 정상 mock으로만 쓰는 경향은 위 커버리지 공백 7번과 동일한 원인** — 이 자체가 "과도한 mock 의존"이라기보다, mock의 스텁 조합이 실패 시나리오 쪽으로 한 번도 확장되지 않은 편향(coverage bias)에 가깝다. `MarketDataClientImplTest`/`RiskRecalculationServiceTest`처럼 예외 스텁을 실제로 쓰는 테스트가 이미 존재하므로, 같은 패턴을 `checkAndSave(Property)`의 `depositSafetyCheckService` 호출에도 추가하는 게 이 코드베이스의 기존 관례에도 맞는다.
3. **플레이키 가능성이 있는 시간 의존 테스트는 발견하지 못했다** — `AdminStatsControllerTest`/`RiskRecalculationServiceTest` 등 날짜 범위를 다루는 테스트는 `LocalDate.now()`를 직접 쓰지 않고 고정된 날짜 문자열(`"2026-01-01"` 등)을 파라미터로 넘기거나 `any()`로 매처를 느슷하게 잡아 실행 시각에 의존하지 않는다. 동시성 테스트(`AdminUserServiceConcurrentDemotionIntegrationTest`, `UserServiceConcurrentNicknameChangeIntegrationTest`, `CustomOAuth2UserServiceConcurrentLoginIntegrationTest` 등)는 `ExecutorService`+`CountDownLatch`/`Future.get()`으로 스레드 완료를 명시적으로 기다리는 패턴을 일관되게 쓰고 있어 sleep 기반의 타이밍 추측에 의존하지 않는다 — 순서 비결정성으로 인한 플레이키는 설계상 낮아 보인다.
