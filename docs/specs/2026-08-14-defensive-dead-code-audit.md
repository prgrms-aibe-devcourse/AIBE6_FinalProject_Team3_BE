# 방어적/미사용 코드(YAGNI 위반) 전수 감사 — Backend — 2026-08-14

## 배경

`User.emailVerified` 필드를 "나중에 재확인 정책이 추가될 때를 대비"라는 이유로 추가했다가, 실제로는
어디서도 읽지 않는 write-only 필드임이 드러나 제거했다. 이 계기로 같은 패턴("일단 만들어뒀지만
실제로는 아무 데도 쓰이지 않는 코드")이 다른 곳에도 있는지 8개 도메인(auth/user/property/checklist/
market-data/risk-analysis/contract-analysis/admin) + 공용(global) 코드 전체를 병렬 에이전트로 감사했다.
이 문서는 그중 **backend(Java/Spring) 코드에 해당하는 항목만** 추린 것이다 — 프론트엔드 대응 항목은
`frontend/docs/specs/2026-08-14-defensive-dead-code-audit.md`에 별도로 정리되어 있고, 서로 얽힌
항목은 아래에서 상대 문서를 명시적으로 가리켰다.

**방법**: 각 필드/메서드/설정값/ErrorCode에 대해 실제 `grep`으로 사용처(호출부/읽는 곳)를 추적. 감으로
판단한 항목은 없음 — "확실함"과 "애매함"을 구분해 표시했다. 코드 수정은 하지 않았고 보고만 했다.

**주의**: 이 문서는 코드가 실제로 바뀌면 낡은 스냅샷이 된다. 판단이 필요할 때는 항상 지금의 소스를
다시 확인할 것.

---

## 요약 — 도메인별 확실한(CONFIRMED) 발견 건수 (backend 기준)

| 도메인 | 확실한 죽은 코드 | 특이사항 |
|---|---|---|
| auth | 4건 | |
| user | 5건 | **실제 버그 1건과 연결**(WOLSE/MONTHLY_RENT — 원인은 frontend, 상세는 frontend 문서 참고) |
| property | 4건 | |
| checklist | 0건 | 가장 깨끗한 도메인 |
| market-data/risk-analysis | 9건 | 가장 지저분한 도메인(enum 분기 다수가 현재 어댑터 구현상 도달 불가) |
| contract-analysis | 8건 | **실제 보안 갭 1건 발견**(매물 소유권 검증이 빈 스텁) |
| admin | 5건 | `admin_audit_logs` 테이블 전체가 write-only |
| global(공용) | 2건 + 미구현 엔드포인트 1건 | frontend가 호출하는 `/users/me/activity-history`가 backend에 없음 |

---

## 1. Auth 도메인

1. **`User.withdrawnAt`** (`src/main/java/com/algogyeyak/user/entity/User.java:56`) — 탈퇴 시각을 저장하지만 `getWithdrawnAt()` 호출부가 프로젝트 전체에 0건. 관리자 화면 어디에도 노출 안 됨(admin 응답 DTO 자체에도 없음).
2. **`JwtProvider.validateToken(String)`** (`JwtProvider.java:57-64`) — 실제 인증 경로는 전부 `parseClaims()`를 직접 호출하고 자체 예외 처리를 함. 호출부는 `JwtProviderTest`뿐.
3. **`OAuth2UserInfo.getAttributes()`** (`OAuth2UserInfo.java:13-15`) — 어디서도 호출 안 됨(비슷한 이름의 Spring Security `OAuth2User.getAttributes()`와 혼동하기 쉬움).
4. **`AuthProvider.LOCAL`** enum 값 (`AuthProvider.java:4`) — `UserSocialAccount`에는 저장 안 하기로 설계돼 있고, `AuthProvider.valueOf(registrationId)`는 "google"/"kakao"로만 호출되어 절대 만들어지지 않음.

**애매함(팀이 이미 의도적으로 남긴 것)**:
- 카카오 이메일 자동연동 경로(`KakaoOAuth2UserInfo.java:28-31,47-51`) — `application.yml`의 카카오 scope가 `profile_nickname`뿐이라 `account_email` 동의항목이 없어 지금은 항상 비활성. "승인되면 다시 추가할 것"이라고 명시된 대기 중인 코드라 죽은 코드로 분류하지 않음.
- **`MeResponse.email`**(`AuthController.java:111,376`) — `/auth/me`, `/auth/signup`, `/auth/login`, `/auth/dev-login` 응답에 전부 포함되지만, frontend 소비처(`getCurrentUser()` 사용처 전부) 어디서도 `.email`을 안 읽음. → frontend 문서 "1. Auth" 항목 참고. 필드를 없애려면 이 응답을 쓰는 다른 소비자가 없는지 frontend와 함께 확인 필요.

---

## 2. User 도메인

1. **`UserProfileResponse.status`** — `UserService.getActiveUserOrThrow()`가 이미 WITHDRAWN/SUSPENDED를 걸러내므로 이 필드 값은 항상 `"ACTIVE"`. frontend 매퍼도 안 읽음(frontend 문서 참고).
2. **`UserSearchCondition.empty()`** — 호출부 0건(테스트 포함).
3. **`UserRepository.countByRoleAndStatus`** — 호출부 0건. 바로 아래 메서드의 Javadoc이 "이걸 락 버전(`findAllByRoleAndStatusForUpdate`)으로 교체했다"고 스스로 언급.
4. **`UserSocialAccount.createdAt`** — `@CreatedDate`로 채워지지만 어디서도 안 읽음.
5. **`UserPreference.createdAt`/`updatedAt`** — 마찬가지로 write-only.

**⚠️ frontend와 연결된 실제 버그(backend 자체는 정상)**:
백엔드 `TransactionType` enum은 `JEONSE, MONTHLY_RENT`로 올바르게 정의돼 있고 `UserService.java:342`가 `.name()`으로 그대로 직렬화한다. 그런데 frontend의 매핑 테이블이 `'WOLSE'` 키만 갖고 있어서, 월세 계정은 조회 시 값이 조용히 빠지고 저장 시 `"WOLSE"`(존재하지 않는 enum 상수)를 보내 Jackson 역직렬화가 실패할 가능성이 높다. **backend 쪽 수정은 불필요**(enum 자체는 맞음) — frontend 매핑 테이블만 고치면 된다. 상세는 frontend 문서의 "2. User" 항목 참고.

---

## 3. Property 도메인

1. **`PropertyImage.sortOrder`** (`entity/PropertyImage.java:41,44,47`) — 요청 DTO(`PropertyImageRequest`)에 이 필드 자체가 없어 항상 null로 생성. `PropertyImageRepository.java:9-14` 주석이 스스로 "정렬 기준으로 못 써서 id 오름차순을 대신 쓴다"고 인정.
2. **`ErrorCode.PROPERTY_REQUIRED_FIELD_MISSING`** (`ErrorCode.java:84`) — throw되는 곳 0건(Bean Validation이 대신 처리 중).
3. **`PropertyReportSearchCondition.empty()`** (`dto/PropertyReportSearchCondition.java:13-15`) — 호출부 0건(테스트 포함).
4. **`KakaoAddressSearchResponse.Meta.totalCount`** (`client/dto/KakaoAddressSearchResponse.java:17-18,26-27`) — 외부 API 응답 역직렬화 필드, `KakaoAddressClientImpl`은 `documents`만 사용.

**애매함**:
- **`ErrorCode.PROPERTY_TYPE_NOT_SUPPORTED`** (`ErrorCode.java:89`) — throw 0건이지만, 코드 자체 주석에 "enum이라 Jackson 단계에서 걸러져 도달 못 함 - 의도적으로 유지"라고 이미 팀이 검토·정당화해둔 케이스(2026-07-28).
- `PropertySearchCondition.empty()` — 프로덕션 미사용, `PropertyServiceTest`에서만 8회 사용(테스트 편의 목적으로 살아있음).
- **`AdminPropertyReportDetailResponse.deposit`/`.monthlyRent`/`.reviewerId`** — admin 도메인 항목과 중복, 상세는 "7. Admin" 참고.
- **`PropertyListResponse.status`/`PropertyDetailResponse.status`** — `findActiveProperty()`/`getMyProperties()`가 삭제된 매물을 이미 걸러내 값이 사실상 항상 `"ACTIVE"`. frontend 매퍼도 안 읽음(frontend 문서 참고).

---

## 4. Checklist 도메인 — 확실한 발견 없음

가장 깨끗한 도메인. `applicablePropertyTypes`, `guideText`, `helperText`, `code`(`ChecklistItemCode` 6종, `OWNERSHIP_ACQUISITION_DATE`는 risk-analysis 도메인의 `DepositSafetyCheckService.java:113`에서 실제로 cross-domain 소비까지 확인됨) 등 최근 추가된 필드까지 전부 실사용 확인됨. `CHECKLIST_*`/`ADMIN_CHECKLIST_TEMPLATE_*` ErrorCode 7개 전부 throw 지점 확인.

**애매함(문서 정합성 이슈, 죽은 코드 아님)**:
- `ChecklistItemTemplate` 클래스 레벨 javadoc(`entity/ChecklistItemTemplate.java:23-24`)이 "매물유형별 분기는 아직 안 둔다"고 서술하는데, 바로 아래(73행) 그 필터 컬럼(`applicablePropertyTypes`)이 이미 구현되어 쓰이고 있음 — 주석만 stale.
- `ChecklistResponse.status`(`dto/ChecklistResponse.java:16`) — 매번 계산해서 내려주지만 frontend 매퍼가 버림(frontend 문서 참고, `domain.ts`의 의도적 설계 코멘트 있음).

---

## 5. Market-data / Risk-analysis 도메인 — 가장 지저분함

1. **`PropertyRiskCheck.policyVersion` / `DepositSafetyCheck.policyVersion`** — 두 테이블 모두 매번 기록하지만 `getPolicyVersion()` 호출부 프로젝트 전체 0건.
2. **`DepositSafetyCheck.failed(...)` 정적 팩토리 + `DepositSafetyStatus.FAILED`** — 실제로는 `unavailable()`/`calculated()`만 호출되어 도달 불가.
3. **`RiskCheckReason.POLICY_CALCULATION_ERROR` / `.INTERNAL_ERROR`** — 어디서도 할당 안 됨. `PriceAnomalyDetector.mapReason()`의 switch가 이 두 값을 절대 반환하지 않음.
4. **`DepositSafetyCheckReason.CALCULATION_DATA_INVALID` / `.INTERNAL_ERROR`** — 어디서도 할당 안 됨.
5. **`MarketComparisonStatus.FAILED`** — `MarketDataClientImpl.toMarketComparison()`의 자체 주석이 "진짜 FAILED 판정은 market-data 선행 작업 이후로 미룸"이라고 명시. 현재는 SUCCESS/UNDETERMINABLE만 반환해 도달 불가. 연쇄적으로 `MarketUnavailableReason.EXTERNAL_API_FAILURE`/`RATE_LIMIT_EXCEEDED`/`INVALID_RESPONSE_FORMAT`도 도달 불가.
6. **`MarketSaleComparisonResponse.message`/`.reason`** — 유일한 소비처(`MarketSaleDataClientImpl.toMarketSalePrice()`)가 non-AVAILABLE 분기에서 `Optional.empty()`만 반환하고 이 값들은 안 읽음. (`MarketComparisonResponse`와 달리 이 DTO는 어떤 컨트롤러도 직접 응답하지 않음 — 순수 내부 전달용인데 그 전달마저 버려짐.)
7. **`RentTransactionSample`/`TradeTransactionSample`의 `buildingName`/`legalDongCode`** — `MolitRentClientImpl`/`MolitTradeClientImpl`이 채우지만 `getBuildingName()`/`getLegalDongCode()` 호출부 0건.
8. **`MolitRentResponse`/`MolitTradeResponse`의 `Item.houseType`, `Response.Header`(`resultCode`/`resultMsg`), `Body.totalCount`** — Jackson이 매 API 응답마다 파싱은 하지만 어떤 서비스도 안 읽음(API 레벨 에러 코드/총건수를 구조적으로 무시하고 있다는 뜻이라, "0건인데 성공으로 취급" 같은 엣지케이스 처리 여부는 한 번 점검해볼 만함).

**애매함**:
- **`RiskPolicyConfig.jeonseRatioAlertOver`**(`policy/RiskPolicyConfig.java:16`, `application.yml:144`) — `getJeonseRatioAlertOver()` 호출부 0건. `DepositSafetyCheckService.buildExplanation()`은 실제로 caution/warnFrom/warnTo 3단계만 쓰는데, 클래스 주석(173-174행)은 "AlertOver까지 관리한다"고 서술 — 4단계로 설계했다가 실수로 빠뜨린 건지, 의도적으로 3단계만 쓰기로 한 건지 확인 필요.
- `SameAccountMultipleDetector`(`policy.isMultiAccountDetectionEnabled()`, 기본 false) — 팀 결정 대기(🔶 논의중)로 명시적으로 꺼둔 기능 플래그. 로직 자체는 살아있어 죽은 코드는 아님, 플래그만 대기 중.

---

## 6. Contract-analysis 도메인

1. **`ContractAnalysisInputService.validatePropertyOwnership`**(`service/ContractAnalysisInputService.java:64-74`) — **완전히 빈 스텁**. `currentUserId` 파라미터를 받지만 아무 검증도 안 함. TODO 주석에 "Property.isOwnedBy(userId)로 연결만 하면 된다"고 적혀 있음. 이 서비스 테스트 파일(`ContractAnalysisInputServiceTest`) 자체가 없음.
2. **`ErrorCode.CONTRACT_ANALYSIS_NOT_RELATED`**(`ErrorCode.java:111`) — throw 0건.
3. **`ErrorCode.CONTRACT_ANALYSIS_FORBIDDEN`**(`ErrorCode.java:112`) — throw 0건(1번이 완성되면 같이 살아날 코드, 위 TODO 주석에서만 언급됨).
4. **`ContractAnalysisAnalyzeRequest.propertyId`** — frontend가 실제로 채워서 보내지만(frontend 문서 참고) `ContractAnalysisAnalyzeService.analyze()` 전체를 읽어도 `request.propertyId()` 호출 지점이 없음 — 서버가 완전히 무시.
5. **`GeminiGenerateContentResponse.Candidate.finishReason` / `.Content.role`**(`client/dto/GeminiGenerateContentResponse.java:14,18`) — Jackson이 파싱만 하고 안 읽힘.
6. **`ContractAnalysisInputResponse.readyForNextStep`**(`dto/ContractAnalysisInputResponse.java:8,12`) — `of()`에서 항상 true로 생성. frontend는 `nextStep`만 검사(frontend 문서 참고).
7. **`ContractAnalysisMaskingResponse.requiresUserConfirmation`**(`dto/ContractAnalysisMaskingResponse.java:6,9`) — `of()`에서 항상 true. 백엔드 테스트가 이를 스스로 검증까지 함(`ContractAnalysisMaskingServiceTest.java:258`, `requiresUserConfirmationIsAlwaysTrue()`).
8. **`ContractAnalysisOcrResponse.editable`**(`dto/ContractAnalysisOcrResponse.java:16`) — `of()`에서 항상 true. `ContractAnalysisOcrServiceTest.java:88`도 이를 검증.

**⚠️ 감사 중 발견한 실제 보안/기능 갭**: 1+3+4번이 합쳐지면 — **"본인이 등록한 매물만 계약 분석에 쓸 수 있다"는 소유권 검증이 입력 단계 DTO/컨트롤러/에러코드까지는 배선됐지만 실제 검증 로직 없이 방치**돼 있음. 다른 사람 매물의 propertyId를 넣어도 현재 코드는 막지 않는다. 우선순위 있게 다룰 것을 권장.

---

## 7. Admin 도메인

1. **`admin_audit_logs` 테이블 전체가 write-only** — `AdminAuditLogRepository`는 `JpaRepository`만 extends(커스텀 조회 메서드 0개), `AdminAuditLogger.java:51`이 `.save()`만 호출. 조회 API/컨트롤러 자체가 없어서, 관리자 액션(권한변경/체크리스트변경/신고처리)마다 DB에 기록은 하지만 아무도 조회하지 않음. `adminEmailSnapshot` 필드("탈퇴해도 당시 누군지 알기 위해")도 테이블을 아무도 안 읽으니 현재는 무의미.
2. **`AdminAuditTargetType`**(`entity/AdminAuditTargetType.java:4-8`) — 클래스 주석이 "나중에 `?targetType=USER` 필터링 API가 생기면 이 컬럼이 필요하다"고 정당화하는데, 그 API가 실제로 없음. `emailVerified`와 정확히 같은 패턴.
3. **`GET /admin/users/{userId}` 상세조회**(`AdminUserController.java:59-62`, `AdminUserService.getDetail()`) — 엔드포인트/서비스/테스트(`AdminUserControllerTest.java:513-540`)는 있지만 frontend 어디서도 호출 안 함(frontend 문서 참고).
4. **`AdminPropertyReportDetailResponse.deposit`/`.monthlyRent`/`.reviewerId`/`.propertyId`** — 응답엔 있지만 상세 모달 렌더링에 frontend가 안 씀(frontend 문서 참고).
5. **`AdminBulkActionResponse.Failure.errorCode`**(`dto/AdminBulkActionResponse.java:12`) — frontend는 `message`만 보여줌(frontend 문서 참고).

**애매함**:
- `AdminChecklistItemTemplateResponse.version` — frontend 화면엔 안 보이지만, 백엔드 내부 `currentActiveMaxVersion()`(`AdminChecklistTemplateService.java:136-141`) 로직에서 실제로 쓰여 완전한 dead code는 아님.
- `@PreAuthorize("hasRole('ADMIN')")`(4개 컨트롤러) — `SecurityConfig`의 URL 레벨 매처(`/admin/**`)로 이미 이중 방어라, 이 메서드 레벨 어노테이션이 단독으로 403을 발생시킨 사례가 테스트/실사용에서 확인된 적 없음. 의도된 defense-in-depth라 죽은 코드로 보긴 어려움.
- `AdminPropertyReportListItemResponse.detail` — 목록 응답에 포함되지만 frontend 목록 테이블엔 안 씀(상세는 별도 API로 다시 받음).

---

## 8. 공용(global) 코드

1. **`S3KeyGenerator.contractImageKey()` + `S3ImagePurpose.CONTRACT`** — contract-analysis 도메인이 S3를 전혀 안 쓰기 때문에(이미지 OCR을 multipart로 직접 처리) 호출될 자리가 없음.
2. **`S3PresignService.deleteObject(String key)`** — public이지만 외부 도메인 서비스에서 호출 0건(클래스 내부에서만 자기 자신을 호출). "계약서 이미지 즉시 삭제용"이라는 주석의 용도 자체가 1번 때문에 발생하지 않음. private으로 좁혀도 무방해 보임.

**`ErrorCode` 전체(79개) 검증**: 위에서 이미 언급한 `PROPERTY_REQUIRED_FIELD_MISSING`/`CONTRACT_ANALYSIS_NOT_RELATED`/`CONTRACT_ANALYSIS_FORBIDDEN` 3개를 제외한 나머지 76개는 전부 최소 1곳 이상에서 실제로 throw됨(`GlobalExceptionHandler`가 프레임워크 예외 처리용으로 쓰는 공용 7개 포함). `build.gradle.kts` 의존성 전체도 미사용 없음(actuator/prometheus는 `application.yml`의 `management.endpoints.web.exposure.include` 설정으로 자동구성 기반 정상 사용 확인).

**⚠️ 감사 중 발견한 실제 프로덕션 갭(backend에 구현이 없음)**: frontend `GET /users/me/activity-history`를 호출하는데(frontend 문서 참고), **`UserController` 전체를 확인해도 이 매핑이 backend에 없음**. frontend가 try/catch로 감싸 빈 배열로 폴백하기 때문에 에러가 겉으로 안 드러날 뿐, "최근 활동 내역"류 화면은 실서비스에서 항상 빈 상태로만 보인다. 이 엔드포인트를 구현할지, 아니면 frontend 쪽 호출을 걷어낼지 결정 필요(이미 알려진 "이메일 인증/비밀번호 재설정 누락"처럼 의도적으로 미룬 스코프일 가능성도 있음).

---

## 다음 단계 제안

- **auth/admin 도메인**의 CONFIRMED 항목은 auth API 담당자가 바로 정리 가능한 범위.
- **user/property/checklist/market-data/risk-analysis/contract-analysis**는 각 담당자 확인 후 정리 권장 — 특히 **contract-analysis의 소유권 검증 누락(보안 갭)**은 우선순위 있게 다룰 것을 권장.
- **`GET /users/me/activity-history` 미구현**은 실사용 여부부터 확인(구현할지 frontend 호출을 뺄지 결정).
