# "내 체크리스트 목록" 조회 API 설계

## 배경 / 출처

- 상단/하단 네비게이션의 "현장 체크" 메뉴가 지금까지 `/properties/1/checklist`로 매물 id를 하드코딩하고 있었음 — 매물마다 체크리스트가 따로 생기는 구조(`Checklist`는 `(user_id, property_id)` 유니크)라 특정 매물로 고정하는 게 잘못됐다는 걸 확인
- "현재 선택된 매물"이라는 개념이 앱에 없어서, "현장 체크" 진입 시점에 어느 매물의 체크리스트를 보여줄지 결정할 방법이 없었음
- 해결책으로 매물별 체크리스트 현황을 한 번에 보여주는 "내 체크리스트 목록" 화면을 새로 만들고, 네비게이션은 이 화면으로 연결하기로 함
- 담당: 본인 (체크리스트 도메인 BE/FE 모두)
- 상태: **확정 — 구현 계획(writing-plans) 진행 가능**

## 범위

- 신규 GET 엔드포인트 1개 추가.
- `Checklist.propertyId`를 `Property` FK로 전환하면서, 체크리스트 생성(`createOrGetChecklist`)에 매물 존재/소유권 검증을 추가한다 (결정 사항 7). 조회(`getChecklist`)/수정(`updateChecklistItem`)/결과 조회(`getChecklistResult`) 로직 자체는 변경하지 않는다.
- 화면 카드에는 상태 배지(시작 전/진행 중/완료)만 보여준다. 진행률 숫자(%, n/m)는 이번 스코프에서 제외 — 나중에 필요해지면 필드만 추가하면 되므로 지금은 내려주지 않는다.

## 결정 사항 (브레인스토밍 중 확정)

1. **목록 범위는 "내 매물 전체"다.** 아직 체크리스트를 시작 안 한 매물도 "시작 전" 카드로 나온다 (체크리스트가 매물 등록 시점이 아니라 체크리스트 페이지 최초 진입 시점에 지연 생성되는 구조라, 시작 전 매물은 `Checklist` row 자체가 없을 수 있음 — 그래도 목록에는 나와야 함).
2. **삭제된 매물은 제외한다.** `PropertyStatus.ACTIVE`인 매물만 대상 (기존 `PropertyRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc`와 동일 기준).
3. **정렬은 매물 최근 등록순(createdAt desc)이다.** `GET /properties`와 동일한 정렬 기준이라 사용자 입장에서 두 목록이 일관돼 보인다.
4. **상태 3종은 기존 `ChecklistStatus`를 그대로 재사용한다.** 새로 계산 로직을 만들지 않는다 — 체크리스트가 없으면 `NOT_STARTED`로 간주, 있으면 `checklist.getStatus()`를 그대로 내려준다.
5. **엔드포인트는 `ChecklistController`에 추가한다.** 응답에 매물 정보(주소/유형)가 섞여 있긴 하지만, 이 API의 주 목적이 "체크리스트 현황 조회"이고 `Property` 도메인이 체크리스트 상태를 알 필요는 없으므로 의존 방향상 `Checklist` 쪽에 두는 게 맞다.
6. **N+1 쿼리를 피한다.** 매물 목록 조회 1번 + 그 유저의 체크리스트 전체 조회 1번, 총 쿼리 2번으로 끝낸다 (매물마다 체크리스트를 개별 조회하지 않음).
7. **`Checklist.propertyId`(순수 Long)를 `Property`와의 실제 FK 연관관계로 바꾼다.** 원래 엔티티 주석에 "Property 엔티티가 생기면 연관관계로 연결한다"고 남겨뒀던 TODO인데, 지금 이 기능이 Checklist와 Property를 실제로 조인하는 첫 기능이라 지금 바꾸는 게 자연스럽다. 이 김에 지금까지 없었던 매물 존재/소유권 검증도 `createOrGetChecklist`에 추가한다 (기존에는 아무 propertyId나 넘기면 존재하지 않거나 남의 매물이어도 체크리스트가 생성됐음 — 잠재 버그였음).

## 검토했으나 채택하지 않은 것

- **진행률 숫자(체크 개수/전체 개수, 또는 %) 포함**: 브레인스토밍 중 논의했으나, 우선 배지만으로 시작하고 실제로 불편하면 그때 필드를 추가하기로 함. 나중에 추가할 경우 개수 대신 퍼센트로 내려주는 쪽을 선호한다는 의견이 있었음 (참고용으로 남겨둠 — 확정된 요구사항은 아님).
- **시작 전 매물의 총 문항 수 채우기**: 활성 템플릿 문항 수를 미리 계산해서 "0/23"처럼 보여주는 방안도 검토했으나, 배지만 보여주기로 하면서 불필요해짐.

## API 계약

### `GET /checklists`

내 매물 전체 + 매물별 체크리스트 현황을 반환한다. 인증 필요 (`@AuthenticationPrincipal JwtUserPrincipal`).

응답 (`ApiResponse<List<ChecklistOverviewResponse>>`), 각 원소:

```
{
  propertyId: number,
  checklistId: number | null,   // 아직 시작 안 했으면 null
  roadAddress: string | null,
  jibunAddress: string | null,
  propertyType: string,         // Property.propertyType.name()
  transactionType: string,      // Property.transactionType.name()
  status: "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED"
}
```

- 매물마다 정확히 1개 원소. 매물 수만큼 배열 길이가 결정된다 (체크리스트 시작 여부와 무관).
- 정렬: 매물 `createdAt desc`.
- 매물이 하나도 없으면 빈 배열을 반환한다 (에러 아님).

## 구현 메모

### 목록 조회 (`GET /checklists`)

- `ChecklistRepository`에 `List<Checklist> findAllByUser_Id(Long userId)` 추가.
- `ChecklistService.listMyChecklists(Long userId)`:
  1. `propertyRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(userId, PropertyStatus.ACTIVE)`로 매물 목록 조회
  2. `checklistRepository.findAllByUser_Id(userId)`를 `propertyId -> Checklist`로 매핑 (Map, FK 전환 후에는 `checklist.getProperty().getId()`가 키)
  3. 매물마다 매칭되는 체크리스트가 있으면 `checklistId = checklist.getId()`, `status = checklist.getStatus()`, 없으면 `checklistId = null`, `status = NOT_STARTED`
- `ChecklistOverviewResponse.from(Property property, Checklist checklist)` 정적 팩토리로 DTO 조립 (checklist는 nullable 허용).
- `PropertyRepository`는 이미 있는 메서드로 충분 — 추가 변경 없음.

### `Checklist.propertyId` → `Property` FK 전환

- `Checklist` 엔티티: `@Column(name = "property_id") private Long propertyId;` → `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "property_id", nullable = false) private Property property;` — 컬럼명(`property_id`)은 그대로 유지하므로 기존 데이터/유니크 제약(`uk_checklist_user_property`)에는 영향 없음.
- `Checklist.createFrom(User user, Long propertyId, ...)` → `Checklist.createFrom(User user, Property property, ...)`로 시그니처 변경.
- `ChecklistRepository.findByUserIdAndPropertyId(Long userId, Long propertyId)` → `findByUserIdAndProperty_Id(Long userId, Long propertyId)` (연관관계 경로 탐색이므로 언더스코어 표기 필요).
- `ChecklistResponse.propertyId`/`from()`: `checklist.getPropertyId()` → `checklist.getProperty().getId()`.
- `ChecklistService.createOrGetChecklist(Long userId, Long propertyId)`: 체크리스트를 새로 만들기 전에 `PropertyService`의 `findActiveProperty` 패턴과 동일하게 매물 존재(`PROPERTY_NOT_FOUND`)/삭제 여부(`PROPERTY_NOT_FOUND`)/소유권(`PROPERTY_ACCESS_DENIED`)을 검증한다 — 기존 `PropertyService`가 쓰는 `ErrorCode`를 그대로 재사용해 에러 응답 형태를 통일한다. `ChecklistService`에 `PropertyRepository` 의존성 추가.
- `ChecklistService.getChecklist(userId, propertyId)`(조회 전용)는 이미 `findByUserIdAndProperty_Id`가 유저 기준으로 필터링하므로 별도 검증을 추가하지 않는다 (검증이 실제로 필요한 지점은 신규 생성 시점뿐).
- 영향받는 기존 테스트: `ChecklistTest`(`createFrom` 시그니처), `ChecklistServiceTest`(매물 존재/소유권 검증 케이스 추가 필요), `ChecklistControllerTest`(픽스처에 `Property` 준비 필요) — 구현 단계에서 전수 확인.

## 남은 작업

- Backend: `Checklist` 엔티티 FK 전환, `ChecklistService.createOrGetChecklist`에 매물 존재/소유권 검증 추가, `ChecklistRepository`에 `findAllByUser_Id` 추가, `ChecklistOverviewResponse` DTO 신규, `ChecklistService.listMyChecklists` 구현, `ChecklistController`에 `GET /checklists` 추가, 영향받는 기존 테스트 수정 + 신규 테스트 작성
- `superpowers:writing-plans`로 구현 계획 작성
