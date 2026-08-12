# 관리자(admin) 도메인 — 구현 현황 정리

## 배경 / 성격

이 문서는 **신규 작성 문서**입니다. auth/checklist/contract-analysis/market-data/property/risk-analysis/user 7개 도메인은 이미 `docs/specs/{도메인}-design.md`가 있었지만, admin 도메인만 별도 design 문서 없이 로컬 전용 작업기록(`docs/`, 레포 밖)만 쌓여 있었습니다. 이번에 그 기록들과 실제 코드를 대조해 처음으로 작성했습니다.

참고한 원본 계획/작업 문서(전부 `docs/specs/`, 레포 밖 로컬 전용 기록):
- `2026-07-31-admin-page-implementation.md` — 유저 관리 / 매물 신고 검토 / 통계 대시보드 최초 구현
- `2026-08-03-admin-dashboard-date-range-and-mock-data.md` — 대시보드 조회기간 선택, mock 데이터
- `2026-08-03-redis-and-admin-review.md` — Redis 도입과 함께 진행된 Auth+Admin 전수 확인
- `2026-08-04-admin-checklist-template-management.md` — 체크리스트 문항 템플릿 관리 기능 신규 추가
- `2026-08-05-admin-checklist-risk-analysis-client-conversion.md`, `2026-08-05-auth-token-admin-audit-and-fixes.md` — 크로스오리진 인증 전환에 따른 admin 화면 client component 전환 및 회귀 수정
- `2026-08-10-residual-risk-fixes-and-admin-api-review.md` — Admin API 12개 전수 문서화, 감사로그 DB 테이블화
- `2026-08-11-todo.md` — 위 작업의 후속 남은 과제
- `03-mvp-features.md`에는 admin 관련 별도 절이 없어 참고하지 않았습니다.

이 도메인은 이미 팀 자체적으로 여러 차례(2026-07-31/08-03/08-05/08-10) 전수조사와 코드 리뷰를 거쳤고, 그 결과 상당수 findings가 이미 수정·문서화되어 있습니다. 이 문서의 "요구사항 대비 실제" 서술은 그 역사를 반영하고, 맨 끝 "전수조사 결과" 섹션은 이번에 코드 기준으로 다시 확인하며 찾은 **새 발견**만 담았습니다.

## 범위

- `com.algogyeyak.admin.*`(통계 집계, 감사로그 인프라) 전체
- 실제 admin 기능(유저 관리/매물 신고 검토/체크리스트 템플릿 관리)은 각 도메인 패키지에 `Admin` 접두사 컨트롤러/서비스/DTO로 구현되어 있어 이들도 함께 다룹니다:
  - `com.algogyeyak.user.controller.AdminUserController` / `service.AdminUserService` / `dto.AdminUser*`
  - `com.algogyeyak.property.controller.AdminPropertyReportController` / `service.AdminPropertyReportService` / `dto.AdminPropertyReport*`
  - `com.algogyeyak.checklist.controller.AdminChecklistTemplateController` / `service.AdminChecklistTemplateService` / `dto.AdminChecklistItemTemplate*`
- **제외**: `POST /auth/dev-login`(관리자로 로그인하는 개발용 백도어)은 auth 도메인 범위라 `auth-design.md`를 참고하세요. 다만 "관리자 권한을 실제로 어떻게 얻는지"는 아래에서 짧게 다룹니다.

## 관리자 권한(Role.ADMIN) 부여 경로

`Role`은 `USER`/`ADMIN` 2가지뿐이고(`user.enums.Role`), 일반 가입 경로(`createLocalUser`/`createOAuthUser`)는 항상 `Role.USER`로 생성됩니다. `Role.ADMIN`을 얻는 경로는 코드상 정확히 2가지입니다.

1. **`AdminAccountSeeder`(앱 기동 시)** — `app.dev-login.enabled`가 켜져 있을 때만 동작. `app.dev-login.email`에 계정이 아직 없으면 `User.createLocalUser(...).grantAdminRole()`로 새 계정을 만들어 시딩합니다. 이미 그 이메일로 계정이 있으면 **어떤 이유로든 무조건 건드리지 않고 건너뜁니다**(과거엔 기존 계정을 조용히 ADMIN으로 승격시키는 "healing" 로직이 있었으나 보안상 제거됨 — `auth-design.md` 참고).
2. **`AdminUserService.updateRole()`(관리자 페이지)** — 이미 `ADMIN`인 다른 관리자가 `PATCH /admin/users/{userId}/role`로 대상 유저의 `role`을 `ADMIN`으로 바꿔줄 수 있습니다(`User.changeRole()`). 이쪽은 `ADMIN→USER` 강등도 허용합니다.

즉 "스스로 가입 시점에 ADMIN이 되는 방법"은 1번(개발용 시더) 하나뿐이고, 그 이후의 모든 승격/강등은 2번(이미 ADMIN인 사람이 관리자 페이지에서 부여)을 거칩니다.

## 인가(authorization) 체계

`SecurityConfig.filterChain()`에 단 한 줄로 걸려 있습니다.

```java
.requestMatchers("/admin/**").hasRole("ADMIN")
```

- 이 매처가 `/admin/**` 전체에 대한 **유일한 인가 지점**입니다. 위 3개 컨트롤러(`AdminUserController`/`AdminPropertyReportController`/`AdminChecklistTemplateController`) + `AdminStatsController` 전부 `@RequestMapping("/admin/...")`로 선언돼 있어 빠짐없이 이 매처에 걸립니다 — 개별 컨트롤러/서비스 메서드에 `@PreAuthorize` 등 메서드 레벨 어노테이션은 전혀 없습니다(URL 패턴 기반 단일 지점 방어).
- `hasRole("ADMIN")`이 통과하려면 인증된 JWT의 role이 `ADMIN`이어야 하는데, `JwtAuthenticationFilter`가 매 요청 DB에서 `User`를 재조회해 role/status를 최신값으로 검증하므로(auth-design.md 참고) 관리자 권한을 뺏긴 유저의 기존 토큰이 만료 전이라도 즉시 `/admin/**` 접근이 막힙니다.
- 인가 실패 시 응답 코드: `accessDeniedHandler`를 명시적으로 등록해뒀기 때문에(2026-07-31 리뷰에서 발견된 "MockMvc는 403, 실제 Tomcat은 401" 불일치 수정), 실제 배포에서도 인증됐지만 권한 부족인 요청은 403으로 응답합니다.

## 주요 기능 — 요구사항 대비 실제

| 기능 | 원 계획(2026-07-31 등) | 실제 구현 |
|---|---|---|
| 유저 관리: 조회+검색 | 이메일/닉네임/권한/상태 필터 | ✅ `GET /admin/users` — email/nickname 부분일치, role/status 정확매칭, 페이지네이션(`Pageable`, 정렬 필드 화이트리스트 `createdAt`/`nickname`/`email`) |
| 유저 관리: 상세 | — | ✅ `GET /admin/users/{id}` |
| 유저 관리: 권한 변경 | USER↔ADMIN | ✅ `PATCH /admin/users/{id}/role` — 자기 자신 대상 차단(컨트롤러), 마지막 활성 관리자 강등 차단(`rejectIfLastActiveAdmin`, 2026-08-10부터 `PESSIMISTIC_WRITE`로 원자화), 탈퇴 유저 대상 차단(`User.changeRole`) |
| 유저 관리: 정지/활성화 | — | ✅ `PATCH /admin/users/{id}/status` — ACTIVE/SUSPENDED만 허용(WITHDRAWN은 본인 탈퇴 전용이라 제외), 자기 자신/마지막 활성 관리자/탈퇴 유저 차단 |
| 매물 신고 검토: 목록/상세 | status/reason 필터 | ✅ `GET /admin/property-reports`, `GET /admin/property-reports/{id}` — propertyId/reporterId가 순수 FK라 서비스가 배치 조회해 주소·신고자 닉네임 합성 |
| 매물 신고 검토: 처리 | RECEIVED→RESOLVED/REJECTED | ✅ `PATCH /admin/property-reports/{id}/review` — RECEIVED 상태에서만 전이 가능, 신고자 본인이 검토자인 경우 차단(`ADMIN_PROPERTY_REPORT_SELF_REVIEW`) |
| 통계 대시보드 | *(계획엔 "핵심 지표 카드 + 추이 + 분포", 기간은 최근 14일 고정 예정)* | ✅ `GET /admin/stats/dashboard` 단일 엔드포인트. **(2026-08-03 확장)** `startDate`/`endDate` 쿼리로 기간 직접 선택 가능(생략 시 최근 14일, 최대 90일) — 계획보다 넓은 기능으로 구현됨 |
| 체크리스트 문항 템플릿 관리 | *(2026-07-31 계획엔 없었음, 2026-08-04에 신규 요청으로 추가)* | ✅ `GET/POST/PATCH/DELETE /admin/checklist-templates` — 전체 목록(비활성 포함), 생성/수정/삭제. 스냅샷 방식(`ChecklistItemTemplate`)이라 여기서의 변경은 이미 만들어진 유저 체크리스트에는 영향 없음 |
| 감사로그 | *(2026-07-31 계획엔 텍스트 로그(`log.info`)만 존재, DB 테이블화는 2026-08-10에 추가 결정)* | ✅ 아래 "감사로그" 섹션 참고 |

## 감사로그(AdminAuditLog) — 실제 구현

- **단일 진입점**: `AdminAuditLogger.log(adminUserId, action, targetId, detail)`. 호출부(Admin*Service)가 이미 `@Transactional` 메서드 안에 있다는 전제로, `TransactionSynchronizationManager.isActualTransactionActive()`를 직접 확인해 트랜잭션 밖에서 호출되면 `IllegalStateException`을 던집니다.
- **정책: 실제 변경과 같은 트랜잭션** — 감사 로그 저장이 실패하면(제약 위반 등) 실제 변경(role 변경, 문항 삭제 등)도 함께 롤백됩니다. "감사 기록을 남길 수 없으면 관리자 변경도 실패해야 한다"는 의도적 정책(`AdminAuditLogger`/`AdminAuditLog` javadoc).
- **누가 언제 무엇을 남기는지** — `AdminAuditAction` enum 6개 전부 실제 호출부에 연결되어 있습니다:

| Action | 호출부 | targetId |
|---|---|---|
| `UPDATE_ROLE` | `AdminUserService.updateRole()` | 대상 유저 id |
| `UPDATE_STATUS` | `AdminUserService.updateStatus()` | 대상 유저 id |
| `CREATE_CHECKLIST_TEMPLATE` | `AdminChecklistTemplateService.create()` | 생성된 템플릿 id |
| `UPDATE_CHECKLIST_TEMPLATE` | `AdminChecklistTemplateService.update()` | 템플릿 id |
| `DELETE_CHECKLIST_TEMPLATE` | `AdminChecklistTemplateService.delete()` | 템플릿 id(삭제 전 캡처) |
| `REVIEW_PROPERTY_REPORT` | `AdminPropertyReportService.review()` | 신고 id |

- 실제 변경을 가하는 관리자 API 6개(위 표) **전부** 감사로그가 붙어 있습니다 — 조회(GET) 계열은 대상이 아닙니다(의도된 설계).
- `detail`은 사람이 읽는 텍스트가 아니라 `Map<String,Object>`를 JSON 문자열로 직렬화해 저장합니다(예: `{"beforeRole":"USER","afterRole":"ADMIN"}`) — 나중에 필드 기준 필터링이 가능하도록.
- `admin_email_snapshot` 컬럼에 **기록 시점**의 관리자 이메일을 스냅샷으로 함께 저장합니다 — 행위자 계정이 나중에 탈퇴/이메일 변경되어도 "당시 누구였는지"가 흐려지지 않도록.
- 기존에 각 컨트롤러가 남기던 `log.info("관리자 액션: ...")` 텍스트 로그는 실시간 관측(Prometheus/Grafana)용으로 **그대로 병행 유지**됩니다 — `admin_audit_logs` 테이블은 "누가 언제 무엇을 바꿨는지" 조회 가능한 영구 이력 용도로 별도 관리됩니다.
- **아직 없는 것**: 이 로그를 실제로 조회하는 API/화면(`GET /admin/audit-logs` 같은 것)이 없습니다 — 저장만 되고 있고, 팀 자체 후속 과제 목록(`2026-08-11-todo.md`)에도 "필요해지면 별도로 논의"로 남아 있는 상태입니다.
- 스키마는 `ddl-auto`(Hibernate) 임시조치에 의존합니다 — Flyway/Liquibase 전환 시 `admin_audit_logs`도 정식 마이그레이션으로 옮겨야 합니다(엔티티 javadoc에 이미 명시).

## 비기능 요구사항 — 대조

| 항목 | 실제 |
|---|---|
| 관리자 권한 검사 | ✅ URL 패턴(`SecurityConfig`) 단일 지점, 매 요청 DB로 role/status 최신값 재확인 |
| 자기 자신 대상 액션 차단 | ✅ 권한 변경(`AdminUserController.rejectSelf`)/정지(동일) — 관리자가 스스로 잠기는 사고 방지 |
| 마지막 활성 관리자 보호 | ✅ 강등/정지 둘 다 차단. **(2026-08-10)** 조회-후-검사 방식의 TOCTOU를 `findAllByRoleAndStatusForUpdate`(`PESSIMISTIC_WRITE`)로 원자화, 두 관리자가 동시에 서로를 강등하는 시나리오를 실제 통합 테스트(`AdminUserServiceConcurrentDemotionIntegrationTest`)로 검증 |
| 신고 검토 셀프리뷰 방지 | ✅ 신고자 본인이 검토자인 경우 `ADMIN_PROPERTY_REPORT_SELF_REVIEW`(409) |
| 감사로그(누가/언제/무엇) | ✅ 위 섹션 참고 — 저장은 완비, 조회 API는 없음 |
| 통계 조회 기간 제한 | ✅ 최대 90일(`toDailyCounts`가 하루 단위로 응답 포인트를 채우는 구현 특성상의 임의 상한) |
| 동시성(TOCTOU) | ⚠️ **의도적으로 감수 중인 3곳** — (1) 체크리스트 문항 `code` 활성 중복 검사(`validateCode`), (2) 마지막 활성 문항 삭제/비활성 방지(`validateNotDeactivatingLastActiveTemplate`), (3) 매물 신고 동시 처리(`review()` — 두 관리자가 같은 신고를 동시에 다른 결과로 처리하면 나중 커밋이 조용히 덮어씀). 전부 "관리자 전용 화면, 저빈도"라는 동일 판단 기준으로 코드 주석에 한계를 명시하고 유지 중 |

## 요구사항에 없던 추가 구현

- **체크리스트 문항 템플릿 관리 전체**(2026-08-04) — 2026-07-31 최초 계획에는 없었던 기능. 사용자 요청으로 추가됨
- **통계 대시보드 조회 기간 직접 선택**(2026-08-03) — 원 계획은 "최근 14일 고정"이었으나 `startDate`/`endDate` 쿼리 파라미터로 확장
- **매물 등록 여부 기반 가입→등록 전환율 분포**(2026-08-03) — 원 계획엔 없던 지표. 처음엔 "역할별 유저 분포"였다가 관리자가 실질적으로 1명뿐인 초기 서비스 단계에서 무의미하다는 피드백으로 교체됨
- **관리자 감사로그 DB 테이블화**(2026-08-10) — 원 계획엔 텍스트 로그(`log.info`)만 있었음. 실시간 관측용 텍스트 로그는 유지한 채 영구 조회 가능한 테이블을 별도로 추가

## 남은 이슈 / 확인 필요 총정리

1. **감사로그 조회 API/화면 없음** — 저장은 되지만 관리자가 "누가 언제 뭘 바꿨는지" 화면에서 볼 방법이 없음(팀 자체 후속 과제로 이미 트래킹 중, `2026-08-11-todo.md`).
2. **동시성(TOCTOU) 3곳** — 위 "비기능 요구사항" 참고. 관리자 전용/저빈도라는 판단으로 의도적으로 감수 중이나, 실사용 중 문제가 되면 DB partial unique index나 비관적 락으로 강화 필요.
3. **DB 스키마 마이그레이션 도구 부재** — Flyway/Liquibase 없이 `ddl-auto`에 의존. `admin_audit_logs`를 포함해 이 도메인이 추가한 컬럼/제약이 운영 MySQL에 자동 반영되지 않음(팀 전체가 이미 인지 중인 이슈, auth-design.md에도 동일 서술).
4. 카카오 email 스코프 미승인 등 auth 도메인 이슈는 이 문서 범위 밖(auth-design.md 참고).

## 전수조사 결과 (2026-08-12)

`com.algogyeyak.admin.*` 전체 + `Admin` 접두사가 붙은 user/property/checklist 도메인의 컨트롤러/서비스/DTO/리포지토리, `SecurityConfig`의 `/admin/**` 인가 배선, `User.grantAdminRole()`/`changeRole()`/`suspend()`/`activate()`를 코드 기준으로 전수조사했다. 이 도메인은 팀이 이미 2026-07-31/08-03/08-05/08-10에 여러 차례 자체 전수조사와 코드 리뷰를 거쳤고(위 "배경" 참고), 그 결과 다수의 findings가 이미 수정·테스트로 보강되어 있었다 — 아래는 그 기존 조사에서 다루지 않은 새 발견만 담았다.

### 버그/정확성

1. 특별히 발견된 이슈 없음. `AdminStatsService`의 날짜 경계 처리(`rangeEnd = end.plusDays(1)`로 종료일 포함), `AdminUserService.rejectIfLastActiveAdmin`의 원자성(`PESSIMISTIC_WRITE`), `AdminPropertyReportService`의 배치 조회(N+1 없음 — `findAllById`로 한 번에 조회), `AdminChecklistTemplateService`의 `code`/`itemType` 짝 검증과 마지막 활성 문항 보호 로직을 코드 레벨로 직접 추적했고, 로직 결함은 찾지 못했다.

### 보안

1. **인가가 URL 패턴 단일 지점에 전적으로 의존한다.** `SecurityConfig.java:99`의 `.requestMatchers("/admin/**").hasRole("ADMIN")` 한 줄이 `AdminStatsController`/`AdminUserController`/`AdminPropertyReportController`/`AdminChecklistTemplateController` 4개 컨트롤러의 유일한 인가 검사다 — 4개 컨트롤러 어디에도 `@PreAuthorize`/`@Secured` 같은 메서드 레벨 어노테이션이 없다(전체 grep으로 확인). 지금은 4개 컨트롤러 전부 `@RequestMapping("/admin/...")`로 선언돼 있어 이 매처를 빠짐없이 타지만, 이중 방어가 전혀 없어 앞으로 누군가 admin 전용 컨트롤러를 다른 prefix로 만들거나 이 매처보다 앞선 `permitAll` 규칙을 잘못 추가하면 감지할 안전망이 없다. 실제로 뚫린 사례는 찾지 못했으나(4개 컨트롤러 전부 정상 매칭 확인), 방어가 한 겹뿐이라는 구조적 특성은 기록해둘 만하다.

### 코드 품질 (중복/구조/일관성)

1. ~~**`AdminStatsSummaryResponse`(`admin/dto/AdminStatsSummaryResponse.java:3-7`)의 필드명이 실제 의미와 어긋난다.** `totalUsers`/`totalProperties`/`pendingReports`라는 이름은 "전체 누적"이나 "현재 대기중인 전체 건수"를 암시하지만, `AdminStatsService.summary()`(`admin/service/AdminStatsService.java:69-76`)의 실제 값은 **선택한 기간(`startDate`~`endDate`) 내에 발생한** 신규 가입자/신규 활성 매물/신규 접수 대기 신고 수다. 이 불일치는 2026-08-03 통계 재설계 당시 이미 한 번 문제가 됐던 것으로 보인다 — 프론트(`AdminDashboardClient.tsx:156-166`)는 라벨을 "신규 가입자"/"신규 활성 매물"/"신규 대기 신고"로 정확히 바꿔 화면상으로는 오해가 없지만, **API 계약(필드명) 자체는 그때도 지금도 바뀌지 않았다.** Swagger 문서나 이 API를 직접 호출하는 다른 소비자는 필드명만 보고 "전체 총 회원수"로 오해할 수 있다 — `periodUsers`/`periodNewProperties`/`periodPendingReports`처럼 기간 스코프를 필드명에 반영하는 것을 권장.~~ — ✅ **(2026-08-12 해결)** `totalUsers`/`totalProperties`/`pendingReports` → `newUsers`/`newProperties`/`newPendingReports`로 필드명 변경(`AdminStatsSummaryResponse`, `AdminStatsControllerTest`, frontend `AdminStatsSummaryDto`/`AdminDashboardClient.tsx`/`adminRepository.ts` mock까지 함께 반영). breaking change라 API 계약이 바뀌었음에 유의.
2. `AdminUserController`/`AdminPropertyReportController`/`AdminChecklistTemplateController` 3곳 모두, 실제 변경 메서드마다 `log.info("관리자 액션: actorId={} action={} ... ", ...)` 형태의 텍스트 로그를 각자 조금씩 다른 포맷 문자열로 직접 작성한다(예: `AdminUserController.java:70-71`, `AdminPropertyReportController.java:66-67`, `AdminChecklistTemplateController.java:51/63/70`). `AdminAuditLogger`가 이미 DB 기록용 공용 진입점을 제공하는 것과 대비되게, 이 실시간 텍스트 로그 쪽은 공용 헬퍼 없이 6곳에 사실상 같은 문자열 패턴이 중복돼 있다 — 포맷을 바꾸려면 6곳을 전부 찾아 고쳐야 한다. `AdminAuditLogger.log()` 호출과 짝을 지어 텍스트 로그까지 함께 남기는 공용 메서드로 합치면 중복이 줄어든다.
