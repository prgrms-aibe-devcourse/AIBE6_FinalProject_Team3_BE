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
| 매물 변경 시 기존 위험신호 결과 최신 아님 처리 | O | ⚠️ 별도의 "최신 아님" 플래그·안내는 없지만, `PropertyUpdatedEvent`로 매물 수정 시 위험신호·전세가율이 곧바로 자동 재계산되어(위 매물 수정 표 참고) 실질적으로 오래된 결과가 화면에 남는 문제는 없음 — 요구사항이 말하는 "구분 표시"까지는 아님 |
| 매물 삭제는 논리 삭제 | O | ✅ |
| 동일 사용자·동일 매물 중복 신고 방지 | O | ✅ |
| 주소 검색 기능 제공 | O | ✅ Kakao 연동 |
| 거래유형별 필요 입력 항목만 표시 | O | ✅ FE 책임 영역, 확인 완료 — 등록/수정 폼 모두 `isMonthlyRent` 조건으로 월세 입력란을 거래유형에 따라 보이거나 숨김(FE `property-design.md` 참고) |
| 시세조회 실패가 등록 실패로 오인되지 않게 안내 | O | ✅ `market-data` 쪽 국토부/카카오 API 호출 실패는 내부에서 흡수해 `UNAVAILABLE`로 degrade — 등록 자체는 항상 성공(`MolitRentClientImpl`/`KakaoRegionCodeClientImpl`이 예외를 삼킴) |
| 신고 사유 중복탐지 문구 구분 안내 | O | ✅ risk-analysis 도메인이 완전히 구현돼 있고(`DuplicateListingDetector`), 신고(`PropertyReport`)와는 완전히 분리된 별도 테이블/서비스로 역할이 나뉘어 있음(위 매물 신고 표 참고) |
| 동일 매물 반복 등록 요청 시 중복 저장 방지 | O | ✅ 도로명주소 없으면 지번주소로 폴백해서 체크(위 참고) |
| 수정 성공 + 위험신호 재계산 실패 구분 | O | 해당 기능 자체가 없어 N/A |
| 목록 조회에 페이지네이션 | O | ✅ `page`/`size`/`sort` 지원(위 참고) |
| 목록 조회 시 불필요한 데이터 미포함 | O | ✅ `PropertyListResponse`가 최소 필드만 포함 |

## 요구사항에 없던 추가 구현

- `PropertyRegisterResponse`/`PropertyDetailResponse`의 `marketComparison`은 `market-data` 도메인이 실제로 계산한 결과다. `radiusMeters`(적용된 반경 단계)까지 포함해서 내려준다 — 자세한 판정 로직은 `market-data-design.md` 참고
- 목록 조회 응답이 `PageResponse<PropertyListResponse>`로 감싸지면서 `totalElements`/`totalPages`/`hasNext` 등 요구사항 문서엔 없던 페이지 메타정보가 함께 내려감
- `PropertyListResponse`에 `checklistProgress`(Integer, 0~100 또는 null) 추가 — 목록 카드에서 매물별 임장 체크리스트 진행률을 보여주기 위함. `ChecklistItemRepository.findProgressByUserId(userId)`가 유저의 모든 체크리스트 문항을 `property.id` 기준 GROUP BY로 한 번에 집계해(`ChecklistProgressProjection`) 엔티티 로딩·N+1 없이 매물 개수와 무관하게 쿼리 1회로 끝남. 체크리스트를 아예 시작 안 한 매물은 null(분모가 없음), 시작했으면 반올림된 정수 퍼센트
- `PropertyListResponse`에 `checkSignalCount`(Integer)/`signalSummary`(String)/`jeonseRatio`(Integer) 추가 — 목록 카드에서 매물별 위험신호 개수·전세가율을 보여주기 위함. `checklistProgress`와 동일하게 `PropertyRiskCheckRepository`/`PropertyRiskRepository`/`DepositSafetyCheckRepository`에 `findAllByProperty_UserId(userId)`를 추가해 유저 전체 매물을 한 번에 조회한 뒤 서비스 레이어에서 propertyId별로 묶는다. GROUP BY로 DB에서 직접 집계하지 않는 이유는 `signalSummary`가 여러 `PropertyRisk.description`을 이어붙인 문자열이라 GROUP_CONCAT류의 DB별 문법 차이(H2/MySQL)를 피하기 위함
- **(#214 완료)** `PropertyImageOrphanCleanupJob`(`property.batch`) — 매물 등록/수정 폼에서 사진을 고르면 즉시 presigned S3 업로드 → confirm까지 끝나버려서(`PropertyImageUploadController`), 사용자가 사진만 올리고 폼을 끝까지 제출하지 않고 이탈하면 `S3PresignService.PENDING_UPLOAD_TAG`가 이미 지워진 상태(확정 처리됨)라 버킷 Lifecycle 규칙도 못 잡는 영구 고아 객체가 남는 문제가 있었다. `@Scheduled` 배치(기본 매일 09:30, `property.image-cleanup.cron` — 서버가 09~18시에만 켜져 있어 새벽 시간대로는 실행 자체가 안 돼서 서버 기동 직후로 잡음)가 S3 `property-images/` prefix 전체와 `PropertyImageRepository`에 실제 참조된 imageUrl 집합을 대조해서, DB에 없으면서 그레이스 기간(기본 24시간, `property.image-cleanup.grace-period-hours`) 이상 지난 객체만 삭제한다. 기존 등록/수정 로직은 전혀 건드리지 않는 완전히 독립된 배치 — 도입 배경은 "매물 사진 S3 업로드 타이밍" 논의(2026-08-18) 참고. **서킷브레이커(`property.image-cleanup.max-delete-ratio`, 기본 0.5)**: 삭제 후보가 전체 대상 객체의 이 비율을 넘으면 이번 실행을 통째로 건너뛰고 경고 로그만 남긴다 — DB 조회가 연결 실패/잘못된 환경 등으로 비정상적으로 비면 "참조된 이미지가 거의 없다"고 오판해 정상 운영 중인 이미지까지 대량 삭제할 수 있다는 게 로컬 테스트 중 실제로 재현됐다(2026-08-18, 실 버킷 객체 65건 전량 삭제). 소량의 정상적인 고아 정리는 이 임계치 아래라 그대로 통과된다. 단, 비율 기반 판단은 전체 객체 수가 적을 때 오히려 오작동한다(예: 정상적으로 남은 고아 2개가 전체 4개 중 절반이면 재앙 상황과 구분이 안 됨) — 전체 객체 수가 표본 하한(`PropertyImageOrphanCleanupJob.MIN_OBJECTS_FOR_CIRCUIT_BREAKER`, 10) 미만이면 서킷브레이커를 아예 적용하지 않는다
- **(#179 완료)** `PropertyListResponse`에 `representativeImageUrl`(String, nullable) 추가 — 목록 카드에 대표 사진을 보여달라는 멘토 피드백 반영. `PropertyImageRepository.findByProperty_IdInOrderByProperty_IdAscIdAsc(propertyIds)`로 **현재 페이지의** 매물 ID만 배치 조회한 뒤 `Map<Long, String>`으로 묶는다 — `checklistProgress`/`checkSignalCount` 등 다른 집계 필드들은 유저의 전체 매물을 배치 조회하는 반면, 이미지는 상대적으로 무거운 데이터라 의도적으로 현재 페이지 범위로만 좁힘. 매물당 대표 이미지는 `sortOrder` 컬럼이 아니라 `id ASC`(가장 먼저 업로드된 이미지)로 정한다 — `PropertyService.applyImages()`가 `sortOrder`를 채운 적이 없어 항상 null이기 때문(전수조사에서 발견한 죽은 컬럼). FE는 #130에서 목록/매물검증 페이지 양쪽에 이 필드를 렌더링.
- `Property`에 `maintenanceFee`(Long, nullable) 컬럼 추가 — 요구사항 Entity 필드 목록(위 표 참고)엔 없던 관리비 항목. 선택 입력이라 `null`(관리비를 아예 안 물어봄)과 `0`(관리비 없음을 명시적으로 입력)을 구분해서 그대로 내려준다. `PropertyRegisterRequest`/`PropertyUpdateRequest`에 `@PositiveOrZero` 검증 필드로 추가되고, `PropertyListResponse`/`PropertyDetailResponse`에도 반영됨. `PropertyRegisterResponse`는 애초에 `deposit`/`monthlyRent`/`area`도 포함하지 않는 최소 응답이라 일관성을 위해 여기엔 추가하지 않음(등록 직후 값 확인은 상세조회로)
- **(#222 완료)** `title`(매물 이름) 필수 해제 — 이름이 아예 없는 건물(신축 원룸 등)도 있어 `PropertyRegisterRequest`/`PropertyUpdateRequest`의 `@NotBlank`를 제거했다. `Property.title` 컬럼 자체는 여전히 `nullable = false`로 두고, `PropertyService.resolveTitle()`이 등록/수정 시 항상 이 값을 채운다 — 비어 있으면 `PropertyType.displayName()`(예: "오피스텔")으로 대체해서 저장하므로, 목록/상세 응답 등 title을 읽는 다른 코드는 null을 신경 쓸 필요가 없다. `PropertyType`은 직렬화(JSON enum name)는 그대로 유지하고 `displayName()`은 서버 내부 fallback 텍스트 생성 전용으로만 사용 — FE가 이미 `property-register.ts`의 `propertyTypeOptions`로 동일한 한글 라벨을 갖고 있음

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
10. ~~이미지 형식/크기 검증 미구현~~ → 형식(확장자 화이트리스트/프로토콜)·개수(최대 10장) 검증은 추가됨. ~~**바이트 크기 검증은 여전히 불가능** — 실제 업로드 인프라(S3 등)가 아직 확정되지 않아서, 담당자가 정해지고 인프라가 붙은 이후에나 처리 가능~~ **(2026-08-12 정정)** `PropertyImageUploadController`(`/properties/images/upload-url`, `/properties/images/confirm`) + `S3PresignService`/`S3ImagePurpose.PROPERTY`로 실제 S3 업로드 인프라와 10MB 크기 검증이 이미 구축됨. 다만 등록/수정 API의 `validateImages()`가 이 경로를 거쳤는지 확인하지 않아, 임의의 외부 http(s) 이미지 URL을 그대로 넣으면 크기 제한 없이 그대로 통과·저장됨 — 인프라·검증 로직은 있지만 등록/수정 API가 강제하지 않아 여전히 우회 가능한 상태(전수조사 결과 보안 2번 참고)
11. ~~등록 이후 이미지 추가/변경/삭제가 불가능함~~ — **(2026-08-12 정정)** `PropertyImageUploadController`(S3 presign/confirm) 도입 이후 등록/수정(`PATCH /properties/{propertyId}`) 모두 `images` 필드로 첨부·교체가 가능해짐(전수조사 결과 도입부 참고)
12. ~~매물 중복 등록 판단이 도로명주소가 있을 때만 동작~~ → 지번주소 폴백으로 해소됨
13. ~~매물 신고 "기타" 사유의 입력값 길이 제한 검증이 없음~~ → 500자 제한(`REPORT_DETAIL_TOO_LONG`)으로 해소됨
14. `PROPERTY_TYPE_NOT_SUPPORTED`는 여전히 죽은 코드. `PROPERTY_INVALID_SEARCH_CONDITION`은 서비스 코드상 size≤0 가드로 연결은 됐지만 `@PageableDefault`를 쓰는 현재 컨트롤러 흐름상 실질적으로 도달하기 어려움
15. ~~단독/다가구 매칭정확도 안내 문구가 `DETACHED_HOUSE`에만 나가고 `MULTI_FAMILY`는 빠져 있음~~ → 둘 다 대상으로 해소됨
16. "권한 없는 사용자에게 존재 여부를 노출하지 않는다"는 요구사항과 달리, 실제로는 404(존재하지 않음)와 403(권한 없음)을 구분해서 응답함
17. 보증금/면적 검증이 "0 이상"이 아니라 "0 초과"(`@Positive`)로 구현되어 있음 — `PropertyRegisterRequest` Javadoc에 의도적 결정으로 문서화되어 있으나, 요구사항 문서와의 차이 자체는 남아있어 확인 필요

## 전수조사 결과 (2026-08-12)

재확인 결과: 기존 "남은 이슈" 중 10·11(이미지 검증/추가 불가)번은 이미 코드상 해소되어 있었다 — `PropertyImageUploadController`(`/properties/images/upload-url`, `/properties/images/confirm`) + `S3PresignService`/`S3ImagePurpose.PROPERTY`(확장자·컨텐츠타입·10MB 크기 제한)로 실제 S3 업로드 플로우가 구축되어 있으며, 등록/수정 모두 `images` 필드로 첨부·교체가 가능하다. 이 문서의 "요구사항에 없던 추가 구현"/"남은 이슈" 섹션이 이 변경 이전 시점 기준으로 남아 있어 실제 코드와 크게 벗어나 있다 — 다음 업데이트 때 해당 항목들을 정리 필요. 아래는 이 재확인 과정에서 새로 발견한 이슈다.

**(2026-08-13 정정)** 위 문단이 원래 "1(제목/`title`)번도 해소됨"이라고 적고 있었으나, "남은 이슈" 목록의 실제 1번은 매매(SALE)/`askingPrice` 부재 항목이라 제목과는 무관하다 — 목록이 개정되며 항목 번호가 밀렸는데 이 문단이 따라가지 못한 허상 참조였다. 실제로 `Property.title`/`PropertyRegisterRequest.title` 필드 자체는 이미 존재하지만(코드로 확인), 이걸 지적하던 원래 "남은 이슈" 항목이 이미 목록에서 빠져 있어 정정할 대상이 없다 — 참조만 제거.

### 버그/정확성

1. 목록 조회 검색조건의 `region`이 LIKE 패턴에 이스케이프 없이 그대로 들어간다(`PropertyRepository.search`, `a.roadAddress LIKE CONCAT('%', :region, '%')` 부분). 사용자가 검색어에 `%`나 `_`를 포함해서 보내면(오타·복붙 등으로) SQL LIKE 와일드카드로 해석되어 의도한 것보다 넓거나 좁게 매칭될 수 있다. 본인 소유 매물만 대상이라 심각한 정보노출은 아니지만 검색 정확도 버그다. 수정 방향: region 값에서 `%`/`_`/이스케이프 문자를 치환하고 `LIKE ... ESCAPE '\'`를 사용.
2. `PropertyImage.sortOrder`(`PropertyImage.java:41`, 빌더 파라미터 `PropertyImage.java:44-48`) 컬럼이 선언돼 있지만 실제로는 어디서도 값이 채워지지 않는다 — `PropertyService.applyImages()`(`PropertyService.java:378-388`)가 `imageUrl`/`roomType`만 설정하고 `sortOrder`는 항상 null이다. `Property.images`(`Property.java:79`, `@OneToMany`)에도 `@OrderBy`가 없어 조회 시 순서를 보장하지 않는다. Frontend `PropertyImageUploader.tsx`의 주석("BE는 순서를 보장하는 컬럼(@OrderBy 등) 없이 저장 시점의 삽입 순서를 그대로 돌려주므로...")이 이 사실을 알고 있고 "대표사진(첫 이미지)" 기능이 여기에 의존한다 — 즉 대표사진 지정이 명시적 순서 컬럼이 아니라 관찰된 동작(삽입 순서 유지)에만 의존하는 상태라, 쿼리 플랜/캐시가 바뀌면 순서가 흔들릴 수 있다. 수정 방향: `sortOrder`를 실제로 채우고 `@OrderBy("sortOrder")`를 추가하거나, 아니면 죽은 필드이니 제거.

### 보안

1. ~~`PropertyImageUploadController.confirm()`(`/properties/images/confirm`, `PropertyImageUploadController.java:53-61`)이 요청받은 `key`가 호출자 소유인지 전혀 검증하지 않는다. ... 수정 방향: 프로필과 동일하게 `confirm()`에 `@AuthenticationPrincipal`을 받아 `S3KeyGenerator.isPropertyImageOwnedBy(userId, key)`(공통 코드에 이미 준비되어 있음)로 소유권 검증을 추가.~~ — ✅ **(2026-08-13 해결 확인)** `dev`를 이 브랜치에 병합하는 과정에서 확인 — property 담당자가 정확히 이 방향대로 이미 수정해 `dev`에 올려뒀다. `confirm()`이 `@AuthenticationPrincipal`을 받아 `S3KeyGenerator.isPropertyImageOwnedBy(principal.userId(), request.key())`를 호출하고, 실패 시 `FILE_KEY_ACCESS_DENIED`를 던진다. 교차 도메인 오용(공통 코드의 `validatePurposePrefix()`)과 같은 purpose·다른 소유자(이번 수정) 두 경로 다 막혀 있다.
2. `PropertyService.validateImages()`(`PropertyService.java:359-376`)는 `imageUrl`이 http(s)이고 허용 확장자로 끝나는지만 확인하며, 그 URL이 실제로 `POST /properties/images/confirm`을 거친 값인지·호출자 소유인지는 전혀 확인하지 않는다. 즉 `POST /properties`/`PATCH /properties/{id}`에 임의의 외부 http(s) 이미지 URL(예: 다른 사이트의 대용량 이미지, 또는 이미 확정된 타인의 매물 이미지 URL)을 그대로 넣어도 그대로 통과·저장된다. 새로 구축된 S3 presign 기반 바이트 크기/컨텐츠타입 검증(`S3PresignService.validateContentLength`/`confirmUpload`)은 "권장 경로"일 뿐 서버가 강제하지 않아, 이 경로를 건너뛰면 크기 제한 없는 임의 URL이 그대로 저장된다는 뜻이다. (참고: 기존 "남은 이슈" 10번은 "업로드 인프라가 없어 크기 검증이 애초에 불가능하다"였는데, 지금은 인프라·검증 로직 자체는 존재하되 등록/수정 API가 그 경로를 타도록 강제하지 않아 여전히 우회 가능하다는 쪽으로 성격이 바뀌었다 — 문서 갱신 필요.)

### 코드 품질 (중복/구조/일관성)

1. `ErrorCode.PROPERTY_REQUIRED_FIELD_MISSING`(`ErrorCode.java:54`)도 `PROPERTY_TYPE_NOT_SUPPORTED`와 같은 패턴의 죽은 에러코드다 — 선언 외에 코드베이스 전체에서 참조되는 곳이 없다(필수값 검증은 실제로 Bean Validation `@NotBlank`/`@NotNull`이 처리하고 일반 400으로 응답됨). 기존 문서 14번이 `PROPERTY_TYPE_NOT_SUPPORTED`만 지적했는데, 동일한 성격의 죽은 코드가 하나 더 있다.
2. `S3KeyGenerator.normalizeExtension`(확장자 화이트리스트 검증)과 `S3PresignService.validateContentType`(Content-Type 화이트리스트 검증)이 서로 독립적으로만 검증되고 상호 일치 여부는 확인하지 않는다 — 예를 들어 `fileExtension="jpg"`, `contentType="image/gif"`처럼 서로 안 맞는 조합도 `S3ImagePurpose.PROPERTY`의 개별 화이트리스트 안에만 들면 presigned URL이 발급된다. 심각한 문제는 아니지만(실제 파일 바이트까지 확인하는 건 아니라 확장자-타입 위장은 애초에 완전히 막기 어려움), 확장자와 Content-Type이 다른 파일이 그대로 저장될 수 있다는 점은 향후 이미지 처리(리사이징 등) 도입 시 참고할 필요가 있다.
