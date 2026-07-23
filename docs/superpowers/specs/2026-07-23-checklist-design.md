# 임장 체크리스트 (checklist) 도메인 설계

## 배경 / 출처

- 기획서 Happy Path / Must-have 기능2 (임장 체크리스트 자동생성)
- 요구사항 명세서 `checklist` 도메인 섹션 (Entity, 4개 유스케이스, 비기능 요구사항)
- 담당: 본인(팀원 파트 분담 — Property/Auth/ContractAnalysis는 다른 팀원 담당)

## 범위

이번 스펙은 `com.algogyeyak.checklist` 패키지 신규 구현. Property 엔티티가 아직 없고(카카오 주소 클라이언트 인프라만 존재), risk-analysis(전세가율 연계) 도메인도 아직 없음 — 두 의존성은 아래 "결정 사항"에 정리된 방식으로 느슨하게 스텁 처리하고, 실제 엔티티가 생기면 이어서 연결한다.

## 결정 사항 (브레인스토밍 중 확정)

1. **propertyId는 단순 `Long` 컬럼**. `@ManyToOne` 연관관계 없음. 매물 존재/접근권한 검증은 지금은 생략(TODO 주석으로 표시), Property 엔티티가 생기면 실제 검증 로직 연결.
2. **userId는 실제 `User` FK**(`@ManyToOne`). User 엔티티는 이미 존재하므로 느슨하게 둘 이유 없음.
3. **템플릿은 DB 테이블(`ChecklistItemTemplate`)로 관리**, 버전(`version`) 컬럼으로 이력 관리. 시드 데이터로 초기 20~24개 항목 삽입.
4. **템플릿의 매물유형·거래유형별 분기는 이번 스코프에서 제외**. 문서에 실제로 타입별로 다른 문항 목록이 제시된 적이 없고, Property 쪽에 타입 enum도 아직 없음. 버전 하나 = 전체 매물 공통으로 시작하고, 실제로 타입별 문항이 필요해지면 그때 `propertyType`/`tradeType` 필터 컬럼을 추가한다 (스키마 확장으로 대응 가능하므로 지금 넣지 않음).
5. **ChecklistItem 상태는 요구사항 명세서 그대로**: `checked`(boolean) + `issueFound`(boolean) + `value`(String, nullable — Y/N·날짜 등 특수 항목용). 프론트엔드 mock의 3단계 `status`(checked/caution/problem) UI와는 다른 모델이며, 프론트 쪽 연동/화면 변경은 이번 스코프 밖(프론트 담당자와 별도 조율 필요).
6. **ChecklistItem은 템플릿을 스냅샷 복사**해서 생성한다(FK 참조 아님) — 이후 템플릿이 새 버전으로 바뀌어도 이미 생성된 체크리스트의 문항 내용은 그대로 유지되어야 하기 때문.
7. **코드 주석**: Backend 구현 시 클래스/메서드 단위로 설명 주석을 단다 (팀원들이 읽고 이해할 수 있도록 — 기본 "주석 최소화" 방침의 예외).

## 엔티티 / 스키마

### ChecklistItemTemplate

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | PK |
| version | int | 템플릿 버전 |
| code | enum (nullable) | 자동 issueFound 규칙이 붙는 항목 식별용 (예: TRUST_REGISTRATION, OWNERSHIP_MATCH, OWNERSHIP_ACQUISITION_DATE, TAX_DELINQUENCY_NOTICE, DATE_OF_CONFIRMATION_REQUEST, RESIDENT_REGISTRATION_REQUEST). 일반 항목은 null. |
| category | enum | INDOOR / NOISE / SAFETY / DOCUMENTS / AREA |
| content | String | 문항 텍스트 |
| guideText | String (nullable) | "왜 확인해야 하는지" 짧은 안내 (등기부등본 세부 항목 등) |
| importance | enum | REQUIRED / GENERAL |
| itemType | enum | CHECK / YES_NO / DATE / DOCUMENT_REQUEST |
| displayOrder | int | 정렬 순서 |
| active | boolean | 현재 버전 활성 여부 |

### Checklist

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | PK |
| user | User (FK) | 소유자 |
| propertyId | Long | 매물 ID (연관관계 없음, TODO: Property 엔티티 연동) |
| templateVersion | int | 생성 시점의 템플릿 버전 |
| status | enum | NOT_STARTED / IN_PROGRESS / COMPLETED |
| createdAt / updatedAt | LocalDateTime | JPA Auditing |

- unique(`user_id`, `property_id`) — 유저+매물 조합당 활성 체크리스트 1개

### ChecklistItem

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | PK |
| checklist | Checklist (FK) | 소속 체크리스트 |
| category, content, guideText, importance, itemType, code, displayOrder | - | 생성 시점 템플릿에서 스냅샷 복사 |
| checked | boolean | 확인 여부 |
| issueFound | boolean | 주의 항목 여부 (자동/수동 반영) |
| value | String (nullable) | YES_NO/DATE 타입 항목의 실제 입력값 |

### 자동 issueFound 규칙 (서비스 레이어, `code` 기준)

- 서류 미제공(DATE_OF_CONFIRMATION_REQUEST/RESIDENT_REGISTRATION_REQUEST가 "미제공"으로 입력) → `issueFound = true`
- TRUST_REGISTRATION 값이 "Y" → `issueFound = true`
- OWNERSHIP_MATCH 값이 "N"(명의 불일치) → `issueFound = true`

## API 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/properties/{propertyId}/checklists` | 체크리스트 생성 (이미 있으면 기존 것 반환 — 멱등) |
| GET | `/properties/{propertyId}/checklists` | 내 체크리스트 조회 (카테고리·중요도순 정렬) |
| PATCH | `/checklists/{checklistId}/items/{itemId}` | 항목 상태 변경 (checked/value 갱신, issueFound 자동계산) |
| GET | `/checklists/{checklistId}/result` | 결과 확인 (필수 확인 누락 수, 주의 항목 수, 미시작 분기) |

기존 프론트엔드 mock의 `GET /checklists/template?tradeType=`(무상태 템플릿 조회)는 이 스펙(매물별 저장 리소스)과 맞지 않아 대체됨 — 프론트 연동은 프론트 담당자와 별도로 맞춘다.

## 유스케이스별 처리 / 에러 케이스

**생성 (POST)**
- Property 존재 검증: 지금은 스킵 (TODO), User는 인증 컨텍스트에서 확인
- (user, property) 조합 기존 체크리스트 있으면 그대로 반환 (중복 생성 안 함)
- 실패: 존재하지 않는 매물 / 접근 권한 없음 / 매물 정보 부족 / 지원하지 않는 매물 유형 → `BusinessException` + 해당 `ErrorCode`

**조회 (GET)**
- 항목을 category → importance(REQUIRED 우선) → displayOrder 순 정렬
- 실패: 존재하지 않는 체크리스트 / 접근 권한 없음 / 삭제된 매물에 연결된 체크리스트(Property 연동 후 처리)

**항목 업데이트 (PATCH)**
- `value` 형식은 `itemType`별로 검증: CHECK는 value 사용 안 함(null), YES_NO는 `"Y"`/`"N"`, DATE는 `yyyy-MM-dd`, DOCUMENT_REQUEST는 `"PROVIDED"`/`"NOT_PROVIDED"`. 형식에 안 맞으면 → 400 (INVALID_INPUT)
- 존재하지 않는 항목/체크리스트 → 404 (NOT_FOUND), 본인 소유 아님 → 403 (FORBIDDEN)
- 저장 실패 시 기존 상태 유지 (트랜잭션 롤백, 화면에 완료로 표시되지 않도록)
- 업데이트 후 `Checklist.status` 재계산: 체크된 항목 0개 → NOT_STARTED, REQUIRED 전부 체크 → COMPLETED, 그 외 → IN_PROGRESS

**결과 확인 (GET result)**
- 필수 확인 누락 수 = REQUIRED 중 checked=false 개수
- 주의 항목 수 = issueFound=true 개수
- 점수/등급 없음, 개수만 제공. 체크된 항목이 하나도 없으면 "체크리스트를 시작해보세요" 분기 응답

## 비기능 요구사항 반영

- 정확성: `Checklist.templateVersion`으로 생성 시점 템플릿 버전 고정 기록
- 데이터 무결성: unique(user, property)로 활성 체크리스트 1개 보장, ChecklistItem은 Checklist 없이 존재 불가(FK not null)
- 성능: 항목 1개 업데이트 시 전체 체크리스트 재생성 안 함(해당 row만 갱신), 조회 시 매물 상세 정보 조인 안 함
- 안정성: 동일 상태값 재요청 시 추가 부작용 없음(멱등 업데이트), 저장 실패 시 UI에 완료로 노출되지 않도록 트랜잭션 처리

## 테스트 계획 (구현 단계에서 상세화)

- `ChecklistItemTemplate` 시드 데이터 로드 확인
- 체크리스트 생성: 최초 생성 / 이미 존재 시 기존 반환(멱등) / 실패 케이스
- 항목 업데이트: 일반 CHECK 항목, YES_NO 항목의 자동 issueFound 규칙(TRUST_REGISTRATION=Y, OWNERSHIP_MATCH=N), 서류 미제공 규칙, 잘못된 value 입력시 400
- 결과 계산: 필수 누락 수 / 주의 항목 수 / 미시작 분기
- `GlobalExceptionHandler`/`BusinessException` 재사용, 기존 `ApiResponse<T>` 포맷 유지

## 남은 의존성 (다른 팀원 파트, 추후 연결)

- Property 엔티티 생성 후: `propertyId` 존재/접근권한/삭제여부 검증 로직 연결
- risk-analysis 도메인(전세가율) 완성 후: "최근 소유권 변경 + 높은 전세가율" 보조 신호 연계 (OWNERSHIP_ACQUISITION_DATE 항목과 결합)
- 프론트엔드: 현재 `/checklist` 화면은 propertyId 없이 정적 mock을 쓰고 있어, 실제 API 연동 시 화면 구조 변경 필요 (프론트 담당자 조율 필요)
