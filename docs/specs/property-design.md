# 매물(property) 도메인 — 구현 현황 정리

## 배경 / 성격

`auth-design.md`, `user-design.md`와 같은 방식의 **회고성(retroactive) 문서**입니다. 원본 요구사항 명세서와 실제 코드를 대조했고, 담당자 확인이 필요한 부분은 **⚠️ 확인 필요**로 표시했습니다.

**범위**: `com.algogyeyak.property.*`(매물 등록/조회/수정/삭제, 매물 신고)만 다룹니다. 이 문서에서 다루는 시세 비교, 위험 신호·안전성 정보, 임장 체크리스트는 매물 도메인이 이들을 "호출/연동하는지 여부"만 확인하며, 각 기능 자체의 상세 구현은 해당 도메인 문서를 따로 참고해야 합니다.

## 주요 Entity — 요구사항 대비 실제

| Entity | 요구사항 | 실제 |
|---|---|---|
| `Property` | id, userId, address, propertyType, transactionType, deposit, monthlyRent, askingPrice, area, status | **`askingPrice`가 없음.** `transactionType`은 `JEONSE`/`MONTHLY_RENT` 둘뿐이라 매매(SALE) 자체를 지원하지 않음 — 서비스 타겟(사회초년생/대학생)에 맞춘 의도적 축소로 보이나 확인 필요 |
| `PropertyAddress` | id, propertyId, roadAddress, jibunAddress, latitude, longitude | 동일 |
| `PropertyImage` | id, propertyId, imageUrl, displayOrder | 거의 동일 — 필드명만 `displayOrder`→`sortOrder` |
| `PropertyReport` | id, propertyId, reporterId, reason, detail, status, createdAt | 동일 |

`PropertyType`은 `OFFICETEL`/`MULTI_FAMILY`/`DETACHED_HOUSE` 세 가지만 존재 — **아파트(APARTMENT) 타입이 없음.** 의도된 범위인지 확인 필요.

## 매물 등록 (`POST /properties`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 필수 입력값 확인 | ✅ (`@NotBlank`/`@NotNull`/`@Positive`) |
| 거래 유형에 맞는 가격 정보 확인 | ✅ `validatePriceCombination` — JEONSE는 monthlyRent 입력 시 거부, MONTHLY_RENT는 monthlyRent 필수 |
| 주소 정규화 + 좌표 변환(Kakao Map API) | ✅ `KakaoAddressClient` |
| 동일 사용자 동일 조건 매물 중복 등록 확인 | **부분 구현** — "동일 사용자 + 동일 거래유형 + (도로명주소 있으면 도로명주소, 없으면 지번주소)"로 체크. 가격/면적은 조건에 포함 안 됨. ~~도로명주소가 없는 경우 중복 체크 자체가 완전히 스킵됨~~ → 지번주소 폴백으로 해소됨(`isDuplicate`) |
| 매물 정보 저장 | ✅ |
| 실거래가 조회 요청 | ✅ `market-data` 도메인(`MarketComparisonService`)이 등록 직후 국토부 실거래가와 실제로 비교해 `marketComparison`에 반영. 오피스텔/연립다세대만 실비교, 단독/다가구·월세는 항상 `UNAVAILABLE`(사유는 `message`) |
| 단독/다가구 지번 비공개 안내 문구 | ✅ `DETACHED_HOUSE`(단독)와 `MULTI_FAMILY`(연립다세대) 둘 다 대상 |
| 실패: 필수 입력값 누락 | ✅ 400 |
| 실패: 잘못된 가격 정보 | ✅ 400 (`PROPERTY_INVALID_PRICE`) |
| 실패: 주소 식별 실패 | ✅ 422 (`PROPERTY_ADDRESS_RESOLUTION_FAILED`) |
| 실패: 지원하지 않는 주택 유형 | ⚠️ 전용 코드(`PROPERTY_TYPE_NOT_SUPPORTED`)는 정의돼 있지만 **실제로는 어디서도 쓰이지 않는 죽은 코드**입니다. `propertyType`이 enum이라 잘못된 값을 보내면 이 코드가 아니라 Jackson 파싱 단계에서 일반 400이 남 |
| 실패: 동일 매물 중복 등록 | ✅ 409 (`PROPERTY_DUPLICATE`) — 단, 위에서 언급한 좁은 판단 기준 내에서만 |
| 실패: 이미지 형식 또는 크기 오류 | **부분 구현** — 확장자 화이트리스트(jpg/jpeg/png/webp/gif) + http(s) 프로토콜 + 최대 10장 검증이 `PROPERTY_IMAGE_INVALID`(400)로 추가됨. **바이트 단위 파일 크기 검증은 여전히 불가능** — 실제 업로드 인프라(S3 등)가 없어 URL을 그대로 받는 구조라서 크기 자체를 알 수 없음 |

## 매물 목록 조회 (`GET /properties`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 인증된 사용자가 등록한 매물만 조회 | ✅ |
| 지역/면적/거래유형/주택유형/가격범위 검색 | ✅ `region`(도로명/지번주소 LIKE 부분일치)/`minArea`·`maxArea`/`transactionType`/`propertyType`/`minDeposit`·`maxDeposit`/`minMonthlyRent`·`maxMonthlyRent` 전부 선택 쿼리파라미터로 지원(`PropertySearchCondition`, `PropertyRepository.search`). 아무것도 안 넘기면 기존과 동일하게 본인 소유 전체 목록. `minMonthlyRent`/`maxMonthlyRent`는 전세 매물의 `monthlyRent`가 항상 null이라 사실상 월세 매물에만 적용됨 |
| 삭제된 매물 제외 | ✅ (`status = ACTIVE` 조건) |
| 페이지네이션 | ✅ `page`/`size`/`sort` 쿼리 파라미터(Spring Data `Pageable`) 지원, 기본값 `size=20`·`createdAt desc`. 응답이 `List<PropertyListResponse>`에서 `PageResponse<PropertyListResponse>`(`content`/`page`/`size`/`totalElements`/`totalPages`/`hasNext`)로 바뀐 **breaking change** — FE는 `$.data[...]`가 아니라 `$.data.content[...]`를 읽어야 함. 정렬은 `createdAt`/`deposit`/`area`만 허용(`PageableUtils.validateSort`), 최대 페이지 크기 100(`PageableUtils.validateMaxSize`) |
| 지도 마커 정보 제공 | ✅ 각 항목에 좌표(`roadAddress`/`jibunAddress`, 위경도는 상세조회에만 포함 — 목록엔 주소 문자열만) — ⚠️ 목록 응답엔 위경도(latitude/longitude)가 없어 "지도 마커"로 바로 쓰기엔 부족할 수 있음, 확인 필요 |
| 위험 신호/전세가율 요약 제공 | ✅ `PropertyListResponse`에 `checkSignalCount`/`signalSummary`/`jeonseRatio` 추가 — `PropertyRiskCheck`(4종 판정 시 항상 upsert)로 "아직 검사 안 함"(null)과 "검사했지만 신호 0건"(0)을 구분하고, `PropertyRisk`(리스크가 실제로 발견된 신호만 저장)를 매물별로 집계해 개수/요약 문자열을 채운다. `jeonseRatio`는 `DepositSafetyCheck.status`가 `CALCULATED`일 때만 값이 있는 percent 정수(상세와 동일하게 "%" 표기는 FE 책임) |
| 실패: 인증 실패 | ✅ 401 |
| 실패: 잘못된 검색 조건 | ✅ 허용되지 않은 정렬 필드는 `INVALID_SORT_FIELD`(400), 페이지 크기 초과(100장 초과)는 `BAD_REQUEST`(400)로 막힘. 면적/보증금/월세 범위의 최소값이 최대값보다 크면 `PROPERTY_INVALID_SEARCH_CONDITION`(400) — 검색 필터가 추가되며 이 코드가 실제로 도달 가능해짐(이전엔 size≤0 가드만 있던 도달 불가능한 방어 코드였음) |

## 매물 상세 조회 (`GET /properties/{propertyId}`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 매물 존재 확인 | ✅ 404(`PROPERTY_NOT_FOUND`, 삭제된 매물 포함) |
| 접근 권한 확인 | ✅ 403(`PROPERTY_ACCESS_DENIED`) |
| 매물 기본 정보 + 주소 정보 조회 | ✅ |
| 실거래가 비교 결과 조회 | ✅ 등록 때와 동일하게 `MarketComparisonService.compare()`를 조회 시점마다 다시 계산해 반환(결과를 저장해두지 않고 매번 실시간 재계산 — `market-data-design.md` 참고) |
| 위험 신호와 안전성 정보 조회 | ✅ **목록은 `PropertyListResponse` 요약 필드로, 상세는 FE가 별도 엔드포인트를 호출하는 방식으로 연동됨.** `PropertyDetailResponse` 자체엔 관련 필드가 없지만, FE `PropertyDetailClient`가 매물 상세를 불러올 때 `GET /properties/{id}/risk-signals`·`GET /properties/{id}/deposit-safety`를 추가로 호출해 화면을 채운다(FE `property-design.md` 참고) — 상세는 매물 1건만 다루면 되므로 목록처럼 응답에 필드를 욱여넣지 않고 API 호출을 나눈 구조로 보임 |
| 임장 체크리스트 생성 여부 확인 | ✅ `PropertyDetailResponse.checklistCreated`(boolean) 추가 — `ChecklistRepository.findByPropertyId(propertyId).isPresent()`로 조회 시점마다 판단 |
| 누적 신고 여부(존재 유무) 조회 | ✅ `PropertyDetailResponse.reported`(boolean) 추가 — `PropertyReportRepository.existsByPropertyIdAndReporterId(propertyId, userId)`로 판단(본인이 신고했는지 기준 — 아래 "자가 플래그 구조" 참고) |
| 실패: 존재하지 않는 매물 | ✅ 404 |
| 실패: 삭제된 매물 | ✅ 404 (존재하지 않는 매물과 동일 코드로 처리) |
| 실패: 접근 권한 없음 | ✅ 403 |

⚠️ **확인 필요**: "권한이 없는 사용자에게 매물의 존재 여부를 노출하지 않는다"는 비기능요구사항이 있는데, 실제로는 **존재하지 않는 매물(404)과 타인 소유 매물(403)을 다른 코드로 구분해서 응답**합니다. 이 자체가 "이 id의 매물이 존재는 한다"는 정보를 간접적으로 노출하는 셈이라, 요구사항과 실제 구현 사이에 방향성 차이가 있습니다.

## 매물 수정 (`PATCH /properties/{propertyId}`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 매물 등록자 여부 확인 | ✅ |
| 수정 입력값 검증 | ✅ (가격/면적만) |
| 주소 변경 시 재정규화 + 좌표 변환 | ❌ **애초에 주소를 수정 대상에서 제외했습니다.** `PropertyUpdateRequest`엔 주소 필드 자체가 없고, 주석상 "주소/매물유형/거래유형은 등록 시 확정값, 바꾸려면 재등록"이 명시적 설계 — 요구사항의 "주소 변경" 시나리오 자체가 발생할 수 없는 구조 |
| 시세·위험신호 재계산 | ✅ 가격/면적 변경 시 `MarketComparisonService.evictCache()`로 시세비교 캐시를 비우고 재계산한다. 위험신호·전세가율은 `PropertyUpdatedEvent`를 발행해 risk-analysis의 `RiskRecalculationService`(`@TransactionalEventListener(AFTER_COMMIT)`)가 구독해 재계산한다 — property는 risk-analysis를 몰라도 되는 이벤트 기반 디커플링. **등록(`POST /properties`) 시에도 동일 이벤트가 발행되도록 최근 수정됨** — 이전엔 등록 직후 목록/상세에서 위험신호/전세가율이 계속 null로 남아있다가 상세·위험분석 페이지를 한 번 열어야만 값이 채워지는 문제가 있었음 |
| 실패: 존재하지 않는 매물 | ✅ 404 |
| 실패: 수정 권한 없음 | ✅ 403 |
| 실패: 잘못된 입력값 | ✅ (가격 조합 검증) |
| 실패: 주소 식별 실패 | N/A — 주소 수정 자체가 불가능해 이 실패 케이스는 발생할 수 없음 |

⚠️ 이미지도 수정 대상에서 빠져 있습니다 — 등록 시 지정한 이미지를 이후에 추가/교체/삭제할 방법이 없습니다.

## 매물 삭제 (`DELETE /properties/{propertyId}`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 매물 등록자 여부 확인 | ✅ |
| 삭제 상태로 변경(soft delete) | ✅ `PropertyStatus.DELETED` |
| 연결된 체크리스트/위험신호 정보를 화면에서 조회 불가 처리 | 부분 구현 — 매물 자체는 조회 시 404 처리되어 화면 노출은 막히지만, **체크리스트 쪽에서 매물이 삭제된 이후에도 여전히 조회 가능한지는 checklist 도메인 문서에서 별도로 확인해야 함**(이 문서 작성 시점 기준 checklist 쪽은 생성 시에만 삭제 여부를 검사하고 조회/수정 시엔 검사하지 않는 것으로 파악됨) |
| 실패: 존재하지 않는 매물 | ✅ 404 |
| 실패: 삭제 권한 없음 | ✅ 403 |
| 실패: 이미 삭제된 매물 | ✅ 409 (`PROPERTY_ALREADY_DELETED`) — 존재하지 않는 매물(404)과 명확히 구분됨 |

## 매물 신고 (`POST /properties/{propertyId}/reports`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 매물 존재/신고 가능 상태 확인 | ✅ 404 (존재하지 않거나 삭제된 매물) |
| 동일 사용자 동일 매물 중복 신고 확인 | ✅ 409 (`REPORT_DUPLICATE`) |
| "기타" 사유 직접 입력 텍스트 길이 검증 | ✅ `ETC` 사유일 때 500자 초과 시 `REPORT_DETAIL_TOO_LONG`(400) — `detail` 컬럼 `@Column(length = 500)`과 동일한 상한. `ETC`가 아닌 사유는 엔티티 생성자에서 `detail`이 어차피 `null`로 버려지므로 검증 대상에서 제외 |
| 신고를 접수 상태로 저장 | ✅ `PropertyReportStatus.RECEIVED` |
| 중복 등록 신고 ≠ risk-analysis 자동 탐지 (역할 분리) | ✅ 코드 주석에 명시적으로 설계 의도가 남아있고, 실제로 `PropertyReport`는 risk-analysis와 완전히 분리된 별도 테이블/서비스 |
| 실패: 존재하지 않는 매물 | ✅ 404 |
| 실패: 삭제된 매물 | ✅ 404 |
| 실패: 동일 사용자의 중복 신고 | ✅ 409 |
| 실패: 신고 사유 누락 | ✅ 400 (`REPORT_REASON_REQUIRED`) |
| 실패: 기타 사유 입력값 길이 초과 | ✅ 400 (`REPORT_DETAIL_TOO_LONG`) |

⚠️ **확인 필요**: 실제 구현은 **"본인이 등록한 매물을 본인이 신고"하는 자가 플래그 구조**입니다(신고 시 소유권 검증(`PROPERTY_ACCESS_DENIED`)이 걸려있어, 타인의 매물은 애초에 신고 대상으로 지정할 수 없음). 이 앱이 애초에 "본인 소유 매물만 조회 가능한 개인 분석 도구" 구조라 자연스러운 설계일 수 있지만, 요구사항 문서의 "누적 신고 여부"·"신고자 정보 비노출" 문구는 여러 사용자가 같은 매물을 볼 수 있는 마켓플레이스 형태를 전제로 한 것처럼 읽혀서, 원래 의도가 무엇이었는지 확인이 필요합니다.

## 비기능 요구사항 — 대조

| 항목 | 요구사항 | 실제 |
|---|---|---|
| 본인만 조회/수정/삭제 | O | ✅ |
| 권한 없는 사용자에게 상세정보/존재여부 미노출 | O | ⚠️ 존재여부는 403/404 구분으로 간접 노출됨(위 참고) |
| 이미지 형식/크기 검증 | O | ⚠️ 부분 구현 — 형식(확장자/프로토콜)·개수는 검증하지만, 실제 업로드 인프라 부재로 크기(바이트) 검증은 불가능 |
| 신고자 식별정보 비노출 | O | ✅ `PropertyReportResponse`에 `reporterId` 없음 (다만 애초에 신고 열람 API 자체가 없어 검증 대상이 사실상 없음) |
| 보증금/월세/가격/면적 0 이상 | O(0 이상) | ⚠️ 실제는 `@Positive`(0 초과) — "0"을 허용하는지 여부가 요구사항과 다름 |
| 거래유형별 필수 가격정보 다르게 검증 | O | ✅ |
| 매물 변경 시 기존 위험신호 결과 최신 아님 처리 | O | 해당 기능 자체가 없어 N/A |
| 매물 삭제는 논리 삭제 | O | ✅ |
| 동일 사용자·동일 매물 중복 신고 방지 | O | ✅ |
| 주소 검색 기능 제공 | O | ✅ Kakao 연동 |
| 거래유형별 필요 입력 항목만 표시 | O | 프론트 책임 영역으로 추정 — 확인 필요 |
| 시세조회 실패가 등록 실패로 오인되지 않게 안내 | O | ✅ `market-data` 쪽 국토부/카카오 API 호출 실패는 내부에서 흡수해 `UNAVAILABLE`로 degrade — 등록 자체는 항상 성공(`MolitRentClientImpl`/`KakaoRegionCodeClientImpl`이 예외를 삼킴) |
| 신고 사유 중복탐지 문구 구분 안내 | O | risk-analysis 자체가 없어 해당 없음 |
| 동일 매물 반복 등록 요청 시 중복 저장 방지 | O | ✅ 도로명주소 없으면 지번주소로 폴백해서 체크(위 참고) |
| 수정 성공 + 위험신호 재계산 실패 구분 | O | 해당 기능 자체가 없어 N/A |
| 목록 조회에 페이지네이션 | O | ✅ `page`/`size`/`sort` 지원(위 참고) |
| 목록 조회 시 불필요한 데이터 미포함 | O | ✅ `PropertyListResponse`가 최소 필드만 포함 |

## 요구사항에 없던 추가 구현

- `PropertyRegisterResponse`/`PropertyDetailResponse`의 `marketComparison`은 `market-data` 도메인이 실제로 계산한 결과다. `radiusMeters`(적용된 반경 단계)까지 포함해서 내려준다 — 자세한 판정 로직은 `market-data-design.md` 참고
- 목록 조회 응답이 `PageResponse<PropertyListResponse>`로 감싸지면서 `totalElements`/`totalPages`/`hasNext` 등 요구사항 문서엔 없던 페이지 메타정보가 함께 내려감
- `PropertyListResponse`에 `checklistProgress`(Integer, 0~100 또는 null) 추가 — 목록 카드에서 매물별 임장 체크리스트 진행률을 보여주기 위함. `ChecklistItemRepository.findProgressByUserId(userId)`가 유저의 모든 체크리스트 문항을 `property.id` 기준 GROUP BY로 한 번에 집계해(`ChecklistProgressProjection`) 엔티티 로딩·N+1 없이 매물 개수와 무관하게 쿼리 1회로 끝남. 체크리스트를 아예 시작 안 한 매물은 null(분모가 없음), 시작했으면 반올림된 정수 퍼센트
- `PropertyListResponse`에 `checkSignalCount`(Integer)/`signalSummary`(String)/`jeonseRatio`(Integer) 추가 — 목록 카드에서 매물별 위험신호 개수·전세가율을 보여주기 위함. `checklistProgress`와 동일하게 `PropertyRiskCheckRepository`/`PropertyRiskRepository`/`DepositSafetyCheckRepository`에 `findAllByProperty_UserId(userId)`를 추가해 유저 전체 매물을 한 번에 조회한 뒤 서비스 레이어에서 propertyId별로 묶는다. GROUP BY로 DB에서 직접 집계하지 않는 이유는 `signalSummary`가 여러 `PropertyRisk.description`을 이어붙인 문자열이라 GROUP_CONCAT류의 DB별 문법 차이(H2/MySQL)를 피하기 위함
- `Property`에 `maintenanceFee`(Long, nullable) 컬럼 추가 — 요구사항 Entity 필드 목록(위 표 참고)엔 없던 관리비 항목. 선택 입력이라 `null`(관리비를 아예 안 물어봄)과 `0`(관리비 없음을 명시적으로 입력)을 구분해서 그대로 내려준다. `PropertyRegisterRequest`/`PropertyUpdateRequest`에 `@PositiveOrZero` 검증 필드로 추가되고, `PropertyListResponse`/`PropertyDetailResponse`에도 반영됨. `PropertyRegisterResponse`는 애초에 `deposit`/`monthlyRent`/`area`도 포함하지 않는 최소 응답이라 일관성을 위해 여기엔 추가하지 않음(등록 직후 값 확인은 상세조회로)

## 남은 이슈 / 확인 필요 총정리

1. 매매(SALE) 거래유형과 `askingPrice`가 아예 없음 — 전월세만 지원. 서비스 타겟에 맞춘 의도적 축소인지 확인
2. ~~국토부 실거래가 연동 자체가 미구현~~ → `market-data` 도메인으로 해소됨(`market-data-design.md` 참고). 남은 건 매물 조회마다 실시간 재계산하는 구조라 트래픽 늘면 캐싱/저장 전환이 필요할 수 있다는 점
3. ~~risk-analysis(위험 신호·안전성 정보) 도메인 자체는 별도로 존재하지만, 매물 상세 응답과는 아직 연동되어 있지 않음~~ → **해소됨.** 목록은 `PropertyListResponse`에 `checkSignalCount`/`signalSummary`/`jeonseRatio`를 추가하는 방식으로, 상세는 FE가 `GET /risk-signals`·`GET /deposit-safety`를 별도로 호출하는 방식으로 각각 연동됨(BE `PropertyDetailResponse` 자체엔 필드가 없지만 실제 화면엔 정상 표시됨 — 위 상세조회 표 참고)
4. ~~매물 상세 응답에 임장 체크리스트 생성 여부가 포함되지 않음~~ → `checklistCreated` 필드 추가로 해소됨
5. ~~매물 상세 응답에 누적 신고 여부가 포함되지 않음~~ → `reported` 필드 추가로 해소됨(단, 아래 6번의 자가 플래그 구조라 "본인이 신고했는지" 기준)
6. 매물 신고가 "본인 매물을 본인이 신고"하는 자가 플래그 구조 — 원래 의도(마켓플레이스식 신고였는지)를 확인 필요
7. ~~매물 목록 조회에 지역/면적/거래유형/주택유형/가격범위 검색이 전혀 없음~~ → `PropertySearchCondition`(region/minArea·maxArea/transactionType/propertyType/minDeposit·maxDeposit/minMonthlyRent·maxMonthlyRent) 추가로 해소됨(페이지네이션은 아래 7-1로 이미 분리 해소돼 있었음)
   - 7-1. ~~페이지네이션이 전혀 없음~~ → `page`/`size`/`sort` 지원으로 해소됨. 단, 응답이 `List`에서 `PageResponse`로 바뀐 breaking change라 FE 연동 확인 필요
8. `PropertyType`에 아파트(APARTMENT)가 없음 — 의도된 범위인지 확인
9. 매물 수정 시 주소/매물유형/거래유형 변경이 애초에 불가능한 구조 — 요구사항의 "주소 변경 시 재정규화" 시나리오 자체가 발생할 수 없음
10. ~~이미지 형식/크기 검증 미구현~~ → 형식(확장자 화이트리스트/프로토콜)·개수(최대 10장) 검증은 추가됨. **바이트 크기 검증은 여전히 불가능** — 실제 업로드 인프라(S3 등)가 아직 확정되지 않아서, 담당자가 정해지고 인프라가 붙은 이후에나 처리 가능
11. 등록 이후 이미지 추가/변경/삭제가 불가능함
12. ~~매물 중복 등록 판단이 도로명주소가 있을 때만 동작~~ → 지번주소 폴백으로 해소됨
13. ~~매물 신고 "기타" 사유의 입력값 길이 제한 검증이 없음~~ → 500자 제한(`REPORT_DETAIL_TOO_LONG`)으로 해소됨
14. `PROPERTY_TYPE_NOT_SUPPORTED`는 여전히 죽은 코드. `PROPERTY_INVALID_SEARCH_CONDITION`은 서비스 코드상 size≤0 가드로 연결은 됐지만 `@PageableDefault`를 쓰는 현재 컨트롤러 흐름상 실질적으로 도달하기 어려움
15. ~~단독/다가구 매칭정확도 안내 문구가 `DETACHED_HOUSE`에만 나가고 `MULTI_FAMILY`는 빠져 있음~~ → 둘 다 대상으로 해소됨
16. "권한 없는 사용자에게 존재 여부를 노출하지 않는다"는 요구사항과 달리, 실제로는 404(존재하지 않음)와 403(권한 없음)을 구분해서 응답함
17. 보증금/면적 검증이 "0 이상"이 아니라 "0 초과"(`@Positive`)로 구현되어 있음 — `PropertyRegisterRequest` Javadoc에 의도적 결정으로 문서화되어 있으나, 요구사항 문서와의 차이 자체는 남아있어 확인 필요
