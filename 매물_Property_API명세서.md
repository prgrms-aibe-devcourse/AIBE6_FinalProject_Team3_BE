# Property API 명세서

기준 문서: 알고계약 요구사항명세서 - 매물(Property) 도메인
담당: B3 (정한결)

---

## 공통 사항

### 공통 응답 포맷

```json
// 성공
{
  "success": true,
  "data": { ... }
}

// 실패
{
  "success": false,
  "error": {
    "code": "PROPERTY_NOT_FOUND",
    "message": "존재하지 않는 매물입니다."
  }
}
```

### Enum 정의

| Enum | 값 | 설명 |
| --- | --- | --- |
| PropertyType | `OFFICETEL`, `MULTI_FAMILY`, `DETACHED_HOUSE` | 오피스텔 / 연립다세대 / 단독다가구 |
| TransactionType | `JEONSE`, `MONTHLY_RENT`, `SALE` | 전세 / 월세 / 매매 |
| PropertyStatus | `ACTIVE`, `DELETED` | 논리 삭제 상태 |
| MarketComparisonStatus | `AVAILABLE`, `UNAVAILABLE`, `FAILED` | 시세 비교 성공 / 시세정보없음(판정불가) / 조회실패 |
| RecalcStatus | `DONE`, `NEEDS_RECALC` | 위험 신호·안전성 정보 재계산 상태 |

### 공통 에러 코드

| 코드 | HTTP Status | 설명 |
| --- | --- | --- |
| `AUTH_REQUIRED` | 401 | 인증되지 않은 사용자 |
| `PROPERTY_NOT_FOUND` | 404 | 존재하지 않거나 삭제된 매물 |
| `PROPERTY_ACCESS_DENIED` | 403 | 본인이 등록하지 않은 매물에 접근/수정/삭제 시도 |
| `PROPERTY_REQUIRED_FIELD_MISSING` | 400 | 필수 입력값 누락 |
| `PROPERTY_INVALID_PRICE` | 400 | 거래 유형에 맞지 않는/잘못된 가격 정보 |
| `PROPERTY_TYPE_NOT_SUPPORTED` | 400 | 지원하지 않는 주택 유형 |
| `PROPERTY_ADDRESS_RESOLUTION_FAILED` | 422 | 주소 식별(Kakao 좌표 변환) 실패 |
| `PROPERTY_DUPLICATE` | 409 | 동일 사용자의 동일 조건 매물 중복 등록 |
| `PROPERTY_IMAGE_INVALID` | 400 | 이미지 형식 또는 크기 오류 |
| `PROPERTY_ALREADY_DELETED` | 409 | 이미 삭제된 매물 |
| `PROPERTY_INVALID_SEARCH_CONDITION` | 400 | 잘못된 검색 조건 |

---
---

# 1. 매물 등록

## API 개요

<aside>
🏠
주소, 매물 유형, 거래 조건 등을 입력받아 매물을 등록하고, Kakao 주소 API로 좌표를 정규화한 뒤 시세 비교 결과까지 함께 반환하는 API입니다.
프론트엔드 매물 등록 화면 연동, Swagger 작성, 매물 등록 테스트 케이스 작성의 기준 문서로 사용합니다.
</aside>

---

## 요구사항 연결

### 관련 기능

- 매물 등록
- 주소 정규화 (Kakao Local API 연동)
- 시세 비교 (등록 시점 부가 정보)

### API 목적

> 사용자가 매물 정보와 주소를 입력하면 Kakao Local API로 주소를 검증·정규화하여 좌표를 확보하고, Property를 생성한 뒤 시세 비교 결과를 함께 반환합니다.
>

### 사용 시나리오

1. 사용자가 매물 등록 화면에서 주소, 매물 유형, 거래 조건, 면적, 설명, 이미지를 입력합니다.
2. 프론트엔드가 매물 등록 API를 호출합니다.
3. 백엔드가 필수 입력값과 거래 유형별 가격 조합을 검증합니다.
4. Kakao Local API로 주소를 조회하여 도로명주소/지번주소/좌표를 확보합니다.
5. 주소 확인에 실패하면 등록을 막고 에러를 반환합니다.
6. Property를 생성하고, 가능하면 시세 비교 결과를 함께 계산합니다.
7. 처리 결과를 응답으로 반환합니다.

---

## Request

### Endpoint

```
POST /properties
```

### Headers

| Key | Value | Required | Description |
| --- | --- | --- | --- |
| Content-Type | application/json | Y | 요청 Body 타입 |
| Accept | application/json | Y | 응답 타입 |
| Authorization | Bearer {accessToken} | Y | 로그인한 사용자만 등록 가능 |

### Path Variables

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| 없음 | - | - | - |

### Query Params

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | - |

### Body

```json
{
  "address": "서울특별시 강남구 테헤란로 123",
  "propertyType": "OFFICETEL",
  "transactionType": "JEONSE",
  "deposit": 30000000,
  "monthlyRent": null,
  "askingPrice": null,
  "area": 23.5,
  "description": "역세권 오피스텔",
  "imageUrls": ["https://cdn.algogyeyak.com/img/abc.jpg"]
}
```

- `deposit` / `monthlyRent` / `askingPrice`는 `transactionType`에 따라 필수 조합이 다름 (JEONSE → deposit 필수, MONTHLY_RENT → deposit + monthlyRent 필수, SALE → askingPrice 필수)
- `imageUrls`는 선택. 이미지는 별도 업로드 엔드포인트에서 먼저 업로드 후 URL만 전달

---

## 시스템 처리

### 처리 흐름

1. 요청 Header와 Body 값을 검증합니다.
2. 주소, 매물 유형, 거래 유형, 가격 정보의 필수 입력 여부를 확인합니다.
3. 거래 유형에 맞는 가격 조합인지 확인합니다.
4. Kakao Local API(`GET https://dapi.kakao.com/v2/local/search/address.json`)로 주소를 조회합니다.
5. 조회 결과가 없거나 API 호출이 실패하면 `PROPERTY_ADDRESS_RESOLUTION_FAILED`를 반환하고 등록을 중단합니다.
6. 도로명주소, 지번주소, 위도, 경도를 확보하여 PropertyAddress를 구성합니다.
7. Property를 생성하고 상태를 `ACTIVE`로 설정합니다.
8. 시세 비교를 시도하고, 성공/데이터없음/실패 여부를 `marketComparison.status`에 반영합니다.
9. 단독/다가구 유형이면 지번 일부 비공개로 매칭 정확도가 낮을 수 있다는 안내를 `notice`에 포함합니다.
10. 성공 또는 실패 응답을 반환합니다.

### Validation

| 항목 | 조건 | 실패 시 응답 |
| --- | --- | --- |
| address | 필수값 | 400 Bad Request (`PROPERTY_REQUIRED_FIELD_MISSING`) |
| address | Kakao API로 주소 확인이 되어야 함 | 422 Unprocessable Entity (`PROPERTY_ADDRESS_RESOLUTION_FAILED`) |
| propertyType | 필수값이며 지원하는 Enum이어야 함 | 400 Bad Request (`PROPERTY_TYPE_NOT_SUPPORTED`) |
| transactionType | 필수값 | 400 Bad Request (`PROPERTY_REQUIRED_FIELD_MISSING`) |
| deposit / monthlyRent / askingPrice | transactionType에 맞는 조합이어야 함 | 400 Bad Request (`PROPERTY_INVALID_PRICE`) |
| area | 필수값이며 0보다 커야 함 | 400 Bad Request (`PROPERTY_REQUIRED_FIELD_MISSING`) |
| imageUrls | 형식이 올바른 URL 목록이어야 함 | 400 Bad Request (`PROPERTY_IMAGE_INVALID`) |
| 동일 사용자 + 동일 주소 + 동일 거래조건 | 중복이면 안 됨 | 409 Conflict (`PROPERTY_DUPLICATE`) |

---

## Response

### `201 Created` (성공 / 부분성공)

```json
{
  "success": true,
  "data": {
    "propertyId": 101,
    "status": "ACTIVE",
    "address": {
      "roadAddress": "서울특별시 강남구 테헤란로 123",
      "jibunAddress": "서울특별시 강남구 역삼동 123-45",
      "latitude": 37.501234,
      "longitude": 127.039876
    },
    "marketComparison": {
      "status": "AVAILABLE",
      "referencePrice": 32000000,
      "differenceRate": -0.0625,
      "sampleCount": 4,
      "referenceDate": "2026-06-30"
    },
    "notice": null
  }
}
```

- `marketComparison.status`가 `UNAVAILABLE` / `FAILED`여도 등록 자체는 성공이므로 `201 Created`를 그대로 반환합니다 (부분 성공 케이스).

### `400 Bad Request`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_REQUIRED_FIELD_MISSING",
    "message": "필수 입력값이 누락되었습니다."
  }
}
```

### `409 Conflict`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_DUPLICATE",
    "message": "이미 동일한 조건으로 등록된 매물이 있습니다."
  }
}
```

### `422 Unprocessable Entity`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_ADDRESS_RESOLUTION_FAILED",
    "message": "입력한 주소를 확인할 수 없습니다."
  }
}
```

### `500 Internal Server Error`

```json
{
  "success": false,
  "error": {
    "code": "INTERNAL_SERVER_ERROR",
    "message": "서버 내부 오류가 발생했습니다."
  }
}
```

---

## 테스트 체크리스트

- [ ]  정상 요청 시 Property가 생성되고 `201 Created` 응답이 반환된다.
- [ ]  등록 성공 시 Kakao API로 조회한 도로명주소/지번주소/좌표가 정확히 반환된다.
- [ ]  시세 비교가 가능한 경우 `marketComparison.status`가 `AVAILABLE`로 반환된다.
- [ ]  시세 데이터가 없어도 등록 자체는 `201 Created`로 성공하고 `status`가 `UNAVAILABLE`/`FAILED`로 반환된다.
- [ ]  주소 누락 시 `400 Bad Request`가 반환된다.
- [ ]  Kakao API 조회 결과가 0건이면 `422 Unprocessable Entity`가 반환된다.
- [ ]  거래 유형에 맞지 않는 가격 조합이면 `400 Bad Request`가 반환된다.
- [ ]  동일 조건으로 중복 등록 시 `409 Conflict`가 반환된다.
- [ ]  단독/다가구 등록 시 `notice`에 지번 비공개 안내 문구가 포함된다.
- [ ]  인증 토큰 없이 호출하면 `401 Unauthorized`가 반환된다.
- [ ]  Swagger / Postman에서 테스트 가능하다.

---

## 참고 메모

- Kakao REST API 키는 소스코드에 포함하지 않고 환경변수(`KAKAO_REST_API_KEY`)로 관리합니다.
- 외부 API(Kakao) 호출에는 응답 시간 제한(timeout)을 적용합니다 (공통 비기능요구사항).
- 이미지 업로드 방식(멀티파트 직접 업로드 vs presigned URL)은 인프라 결정에 따라 `imageUrls` 처리 방식을 조정할 수 있습니다.

---
---

# 2. 매물 목록 조회

## API 개요

<aside>
📋
로그인한 사용자가 등록한 매물을 조건별로 검색·페이지네이션하여 조회하는 API입니다.
프론트엔드 매물 목록 화면 연동, Swagger 작성, 목록 조회 테스트 케이스 작성의 기준 문서로 사용합니다.
</aside>

---

## 요구사항 연결

### 관련 기능

- 매물 목록 조회
- 조건 검색 (지역/면적/거래유형/매물유형/가격)
- 페이지네이션

### API 목적

> 인증된 사용자 본인이 등록한 매물 목록을, 지역·면적·거래유형·매물유형·가격 조건으로 검색하고 페이지 단위로 조회합니다.
>

### 사용 시나리오

1. 사용자가 매물 목록 화면에 진입합니다.
2. 프론트엔드가 검색/필터 조건과 페이지 정보를 Query Param으로 담아 API를 호출합니다.
3. 백엔드가 검색 조건 유효성을 확인합니다.
4. 로그인한 사용자 본인이 등록했고 삭제되지 않은 매물만 조회합니다.
5. 조건에 맞는 매물을 페이지네이션하여 반환합니다.

---

## Request

### Endpoint

```
GET /properties
```

### Headers

| Key | Value | Required | Description |
| --- | --- | --- | --- |
| Accept | application/json | Y | 응답 타입 |
| Authorization | Bearer {accessToken} | Y | 본인 매물만 조회 |

### Path Variables

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| 없음 | - | - | - |

### Query Params

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| region | string | N | - | 지역 검색 |
| areaMin | number | N | - | 면적 하한 |
| areaMax | number | N | - | 면적 상한 |
| transactionType | enum | N | - | 전세 / 월세 / 매매 |
| propertyType | enum | N | - | 오피스텔 / 연립다세대 / 단독다가구 |
| priceMin | number | N | - | 가격 하한 |
| priceMax | number | N | - | 가격 상한 |
| page | int | N | 0 | 페이지 번호 |
| size | int | N | 20 | 페이지 크기 |
| sort | string | N | createdAt,desc | 정렬 기준 |

### Body

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| 없음 | - | - | - |

---

## 시스템 처리

### 처리 흐름

1. 인증 토큰을 검증합니다.
2. Query Param의 형식과 값 범위를 검증합니다(가격/면적 하한이 상한보다 크지 않은지 등).
3. 로그인한 사용자 본인이 등록했고 상태가 `ACTIVE`인 매물만 조회 대상으로 필터링합니다.
4. 검색 조건을 적용해 매물을 조회합니다.
5. 페이지네이션 결과와 함께 응답을 반환합니다.

### Validation

| 항목 | 조건 | 실패 시 응답 |
| --- | --- | --- |
| transactionType / propertyType | 지원하는 Enum 값이어야 함 | 400 Bad Request (`PROPERTY_INVALID_SEARCH_CONDITION`) |
| areaMin / areaMax, priceMin / priceMax | 하한이 상한보다 클 수 없음 | 400 Bad Request (`PROPERTY_INVALID_SEARCH_CONDITION`) |
| page / size | 0 이상의 정수여야 함 | 400 Bad Request (`PROPERTY_INVALID_SEARCH_CONDITION`) |

---

## Response

### `200 OK`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "propertyId": 101,
        "address": "서울특별시 강남구 테헤란로 123",
        "propertyType": "OFFICETEL",
        "transactionType": "JEONSE",
        "deposit": 30000000,
        "area": 23.5,
        "latitude": 37.501234,
        "longitude": 127.039876,
        "marketComparisonStatus": "AVAILABLE"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

- 검색 결과가 없으면 `content: []`, `200 OK`를 그대로 반환합니다 (실패 아님).

### `400 Bad Request`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_INVALID_SEARCH_CONDITION",
    "message": "검색 조건이 올바르지 않습니다."
  }
}
```

### `401 Unauthorized`

```json
{
  "success": false,
  "error": {
    "code": "AUTH_REQUIRED",
    "message": "인증이 필요합니다."
  }
}
```

---

## 테스트 체크리스트

- [ ]  조건 없이 호출 시 본인이 등록한 매물 목록이 반환된다.
- [ ]  삭제된(`DELETED`) 매물은 목록에서 제외된다.
- [ ]  다른 사용자가 등록한 매물은 조회되지 않는다.
- [ ]  region/areaMin/areaMax/transactionType/propertyType/priceMin/priceMax 조합 검색이 정상 동작한다.
- [ ]  검색 결과가 없으면 `200 OK`와 빈 배열이 반환된다.
- [ ]  page/size에 따른 페이지네이션이 정확히 동작한다.
- [ ]  잘못된 검색 조건(하한 > 상한 등) 전달 시 `400 Bad Request`가 반환된다.
- [ ]  인증 토큰 없이 호출하면 `401 Unauthorized`가 반환된다.
- [ ]  Swagger / Postman에서 테스트 가능하다.

---

## 참고 메모

- 기본 정렬은 최신 등록순(`createdAt,desc`)입니다.
- 목록 응답에는 상세 조회에만 포함되는 위험신호/안전성 정보는 제외합니다 (목록 응답 크기 최소화).

---
---

# 3. 매물 상세 조회

## API 개요

<aside>
🔍
매물 하나의 전체 정보(주소, 가격, 이미지, 시세 비교, 위험 신호, 전세가율 등)를 조회하는 API입니다.
프론트엔드 매물 상세 화면 연동, Swagger 작성, 상세 조회 테스트 케이스 작성의 기준 문서로 사용합니다.
</aside>

---

## 요구사항 연결

### 관련 기능

- 매물 상세 조회
- 시세 비교 결과 조회
- 위험 신호 조회
- 전세가율(보증금 안전성) 조회
- 체크리스트 존재 여부 조회

### API 목적

> 매물 ID로 매물의 전체 상세 정보와 부가 분석 정보(시세 비교, 위험 신호, 보증금 안전성, 체크리스트 존재 여부)를 함께 조회합니다.
>

### 사용 시나리오

1. 사용자가 매물 목록에서 매물을 선택합니다.
2. 프론트엔드가 `propertyId`로 상세 조회 API를 호출합니다.
3. 백엔드가 매물 존재 여부와 소유자 여부를 확인합니다.
4. 매물 상세 정보와 시세 비교, 위험 신호, 보증금 안전성, 체크리스트 존재 여부를 함께 반환합니다.

---

## Request

### Endpoint

```
GET /properties/{propertyId}
```

### Headers

| Key | Value | Required | Description |
| --- | --- | --- | --- |
| Accept | application/json | Y | 응답 타입 |
| Authorization | Bearer {accessToken} | Y | 본인 매물만 조회 |

### Path Variables

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| propertyId | long | Y | 조회할 매물 ID |

### Query Params

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | - |

---

## 시스템 처리

### 처리 흐름

1. 인증 토큰을 검증합니다.
2. `propertyId`로 매물을 조회합니다.
3. 매물이 없거나 삭제된 경우 `PROPERTY_NOT_FOUND`를 반환합니다.
4. 매물 소유자가 요청자 본인이 아니면 `PROPERTY_ACCESS_DENIED`를 반환합니다.
5. 매물 기본 정보, 주소, 이미지를 조회합니다.
6. 시세 비교, 위험 신호, 보증금 안전성, 체크리스트 존재 여부를 함께 조회합니다.
7. 응답을 반환합니다.

### Validation

| 항목 | 조건 | 실패 시 응답 |
| --- | --- | --- |
| propertyId | 존재하고 삭제되지 않은 매물이어야 함 | 404 Not Found (`PROPERTY_NOT_FOUND`) |
| propertyId | 요청자 본인이 등록한 매물이어야 함 | 403 Forbidden (`PROPERTY_ACCESS_DENIED`) |

---

## Response

### `200 OK`

```json
{
  "success": true,
  "data": {
    "propertyId": 101,
    "propertyType": "OFFICETEL",
    "transactionType": "JEONSE",
    "deposit": 30000000,
    "monthlyRent": null,
    "askingPrice": null,
    "area": 23.5,
    "description": "역세권 오피스텔",
    "images": ["https://cdn.algogyeyak.com/img/abc.jpg"],
    "address": {
      "roadAddress": "서울특별시 강남구 테헤란로 123",
      "jibunAddress": "서울특별시 강남구 역삼동 123-45",
      "latitude": 37.501234,
      "longitude": 127.039876
    },
    "marketComparison": {
      "status": "AVAILABLE",
      "referencePrice": 32000000,
      "differenceRate": -0.0625
    },
    "riskSignals": {
      "status": "AVAILABLE",
      "signals": [
        { "signalType": "PRICE_OUTLIER", "description": "시세보다 20% 낮은 가격이에요 — 왜 이렇게 저렴한지 확인해보세요" }
      ]
    },
    "depositSafety": {
      "status": "CALCULATED",
      "jeonseRatio": 0.82,
      "explanation": "이 집 전세가율은 82%예요..."
    },
    "checklist": { "exists": true, "checklistId": 55 }
  }
}
```

### `403 Forbidden`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_ACCESS_DENIED",
    "message": "해당 매물에 접근할 권한이 없습니다."
  }
}
```

### `404 Not Found`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_NOT_FOUND",
    "message": "존재하지 않는 매물입니다."
  }
}
```

- 존재하지 않는 경우와 삭제된 경우를 동일한 코드로 통일하여 매물 존재 여부 노출을 방지합니다.

---

## 테스트 체크리스트

- [ ]  본인이 등록한 매물 조회 시 전체 상세 정보가 반환된다.
- [ ]  시세 비교, 위험 신호, 보증금 안전성, 체크리스트 존재 여부가 함께 반환된다.
- [ ]  존재하지 않는 `propertyId` 조회 시 `404 Not Found`가 반환된다.
- [ ]  삭제된 매물 조회 시에도 `404 Not Found`(동일 코드)가 반환된다.
- [ ]  다른 사용자의 매물 조회 시 `403 Forbidden`이 반환된다.
- [ ]  인증 토큰 없이 호출하면 `401 Unauthorized`가 반환된다.
- [ ]  Swagger / Postman에서 테스트 가능하다.

---

## 참고 메모

- 위험 신호/보증금 안전성 계산 로직은 각각 별도 도메인(시세/위험분석)에서 담당하며, 이 API는 그 결과를 조합해 반환하는 역할만 합니다.
- `checklist.exists`가 `false`면 프론트엔드에서 체크리스트 생성 유도 UI를 노출합니다.

---
---

# 4. 매물 수정

## API 개요

<aside>
✏️
매물의 일부 필드를 수정하고, 주소·가격 등 핵심 정보가 바뀌면 시세·위험신호·안전성 정보를 자동으로 재계산 트리거하는 API입니다.
프론트엔드 매물 수정 화면 연동, Swagger 작성, 수정 테스트 케이스 작성의 기준 문서로 사용합니다.
</aside>

---

## 요구사항 연결

### 관련 기능

- 매물 수정
- 주소 재정규화 (주소 변경 시)
- 시세/위험신호/안전성 재계산 트리거

### API 목적

> 매물 소유자가 변경할 필드만 전달하여 매물 정보를 수정하고, 주소·가격·거래유형처럼 분석 결과에 영향을 주는 필드가 바뀌면 관련 정보를 자동으로 재계산합니다.
>

### 사용 시나리오

1. 사용자가 매물 상세/수정 화면에서 일부 항목을 변경합니다.
2. 프론트엔드가 변경된 필드만 담아 수정 API를 호출합니다.
3. 백엔드가 매물 존재 여부, 소유자 여부, 입력값을 검증합니다.
4. 주소가 변경되었으면 Kakao API로 재정규화합니다.
5. 가격/거래유형/주소가 변경되었으면 시세·위험신호·안전성 재계산을 트리거합니다.
6. 처리 결과를 응답으로 반환합니다.

---

## Request

### Endpoint

```
PATCH /properties/{propertyId}
```

### Headers

| Key | Value | Required | Description |
| --- | --- | --- | --- |
| Content-Type | application/json | Y | 요청 Body 타입 |
| Accept | application/json | Y | 응답 타입 |
| Authorization | Bearer {accessToken} | Y | 본인 매물만 수정 가능 |

### Path Variables

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| propertyId | long | Y | 수정할 매물 ID |

### Query Params

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | - |

### Body (변경할 필드만 전달)

```json
{
  "deposit": 28000000,
  "area": 24.0
}
```

---

## 시스템 처리

### 처리 흐름

1. 인증 토큰을 검증합니다.
2. `propertyId`로 매물을 조회하고, 존재 여부와 소유자 여부를 확인합니다.
3. 전달된 필드만 검증합니다(가격 조합, 매물 유형 등).
4. `address`가 변경되었으면 Kakao Local API로 재조회하여 좌표를 갱신합니다.
5. 주소 재정규화에 실패하면 `PROPERTY_ADDRESS_RESOLUTION_FAILED`를 반환하고 수정을 중단합니다.
6. 변경사항을 저장합니다.
7. 가격/거래유형/주소가 바뀐 경우 시세·위험신호·안전성 재계산을 트리거합니다.
8. 재계산 성공/실패 여부와 무관하게 수정 자체는 성공으로 응답하되, `riskRecalc` 필드로 재계산 상태를 알립니다.

### Validation

| 항목 | 조건 | 실패 시 응답 |
| --- | --- | --- |
| propertyId | 존재하고 삭제되지 않은 매물이어야 함 | 404 Not Found (`PROPERTY_NOT_FOUND`) |
| propertyId | 요청자 본인이 등록한 매물이어야 함 | 403 Forbidden (`PROPERTY_ACCESS_DENIED`) |
| address (변경 시) | Kakao API로 주소 확인이 되어야 함 | 422 Unprocessable Entity (`PROPERTY_ADDRESS_RESOLUTION_FAILED`) |
| deposit / monthlyRent / askingPrice (변경 시) | transactionType에 맞는 조합이어야 함 | 400 Bad Request (`PROPERTY_INVALID_PRICE`) |
| 전달된 필드 | 최소 1개 이상이어야 함 | 400 Bad Request (`PROPERTY_REQUIRED_FIELD_MISSING`) |

---

## Response

### `200 OK` (성공 / 부분성공)

```json
{
  "success": true,
  "data": {
    "propertyId": 101,
    "updatedFields": ["deposit", "area"],
    "marketComparison": {
      "status": "AVAILABLE",
      "referencePrice": 32000000,
      "differenceRate": -0.0125
    },
    "riskRecalc": "DONE"
  }
}
```

- 재계산이 실패해도 매물 수정 자체는 `200 OK`, `riskRecalc: "NEEDS_RECALC"`로 부분성공을 표현합니다.

### `400 Bad Request`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_INVALID_PRICE",
    "message": "거래 유형에 맞지 않는 가격 정보입니다."
  }
}
```

### `403 Forbidden`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_ACCESS_DENIED",
    "message": "해당 매물을 수정할 권한이 없습니다."
  }
}
```

### `404 Not Found`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_NOT_FOUND",
    "message": "존재하지 않는 매물입니다."
  }
}
```

### `422 Unprocessable Entity`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_ADDRESS_RESOLUTION_FAILED",
    "message": "변경한 주소를 확인할 수 없습니다."
  }
}
```

---

## 테스트 체크리스트

- [ ]  일부 필드만 전달해도 해당 필드만 정상적으로 수정된다.
- [ ]  주소를 변경하면 Kakao API로 좌표가 재정규화된다.
- [ ]  가격/거래유형/주소 변경 시 시세 재계산이 트리거되고 `riskRecalc: "DONE"`이 반환된다.
- [ ]  재계산이 실패해도 수정 자체는 `200 OK`, `riskRecalc: "NEEDS_RECALC"`로 반환된다.
- [ ]  변경 필드 없이 호출하면 `400 Bad Request`가 반환된다.
- [ ]  거래 유형에 맞지 않는 가격으로 수정 시 `400 Bad Request`가 반환된다.
- [ ]  존재하지 않는 매물 수정 시 `404 Not Found`가 반환된다.
- [ ]  다른 사용자의 매물 수정 시 `403 Forbidden`이 반환된다.
- [ ]  변경한 주소를 Kakao API가 확인하지 못하면 `422 Unprocessable Entity`가 반환된다.
- [ ]  Swagger / Postman에서 테스트 가능하다.

---

## 참고 메모

- 재계산 실패 케이스에 대한 수동 재시도 API(`POST /properties/{id}/recalculate`) 추가 여부는 팀과 확정 필요.
- 부분 필드 수정(PATCH)이므로 요청 Body에 없는 필드는 기존 값을 유지합니다.

---
---

# 5. 매물 삭제

## API 개요

<aside>
🗑️
매물을 논리 삭제(soft delete)하는 API입니다.
프론트엔드 매물 삭제 화면 연동, Swagger 작성, 삭제 테스트 케이스 작성의 기준 문서로 사용합니다.
</aside>

---

## 요구사항 연결

### 관련 기능

- 매물 삭제 (논리 삭제)

### API 목적

> 매물 소유자가 자신의 매물을 삭제 요청하면, 실제로 레코드를 지우지 않고 상태를 `DELETED`로 변경하여 이후 조회에서 제외합니다.
>

### 사용 시나리오

1. 사용자가 매물 상세 화면에서 삭제를 요청합니다.
2. 프론트엔드가 `propertyId`로 삭제 API를 호출합니다.
3. 백엔드가 매물 존재 여부, 소유자 여부, 이미 삭제되었는지 여부를 확인합니다.
4. 매물 상태를 `DELETED`로 변경합니다.
5. 처리 결과를 응답으로 반환합니다.

---

## Request

### Endpoint

```
DELETE /properties/{propertyId}
```

### Headers

| Key | Value | Required | Description |
| --- | --- | --- | --- |
| Accept | application/json | Y | 응답 타입 |
| Authorization | Bearer {accessToken} | Y | 본인 매물만 삭제 가능 |

### Path Variables

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| propertyId | long | Y | 삭제할 매물 ID |

### Query Params

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | - |

---

## 시스템 처리

### 처리 흐름

1. 인증 토큰을 검증합니다.
2. `propertyId`로 매물을 조회합니다.
3. 매물이 없으면 `PROPERTY_NOT_FOUND`를 반환합니다.
4. 매물 소유자가 요청자 본인이 아니면 `PROPERTY_ACCESS_DENIED`를 반환합니다.
5. 이미 삭제된 매물이면 `PROPERTY_ALREADY_DELETED`를 반환합니다.
6. 매물 상태를 `DELETED`로 변경합니다 (논리 삭제).
7. 연결된 Checklist/RiskSignal/DepositSafetyCheck는 DB에는 유지하되, 사용자 조회 API에서는 제외 처리합니다.
8. 처리 결과를 응답으로 반환합니다.

### Validation

| 항목 | 조건 | 실패 시 응답 |
| --- | --- | --- |
| propertyId | 존재하는 매물이어야 함 | 404 Not Found (`PROPERTY_NOT_FOUND`) |
| propertyId | 요청자 본인이 등록한 매물이어야 함 | 403 Forbidden (`PROPERTY_ACCESS_DENIED`) |
| propertyId | 아직 삭제되지 않은 매물이어야 함 | 409 Conflict (`PROPERTY_ALREADY_DELETED`) |

---

## Response

### `200 OK`

```json
{
  "success": true,
  "data": {
    "propertyId": 101,
    "status": "DELETED"
  }
}
```

### `403 Forbidden`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_ACCESS_DENIED",
    "message": "해당 매물을 삭제할 권한이 없습니다."
  }
}
```

### `404 Not Found`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_NOT_FOUND",
    "message": "존재하지 않는 매물입니다."
  }
}
```

### `409 Conflict`

```json
{
  "success": false,
  "error": {
    "code": "PROPERTY_ALREADY_DELETED",
    "message": "이미 삭제된 매물입니다."
  }
}
```

---

## 테스트 체크리스트

- [ ]  본인 매물 삭제 요청 시 상태가 `DELETED`로 변경되고 `200 OK`가 반환된다.
- [ ]  삭제 후 목록/상세 조회에서 해당 매물이 조회되지 않는다(`404 Not Found`).
- [ ]  존재하지 않는 매물 삭제 시 `404 Not Found`가 반환된다.
- [ ]  다른 사용자의 매물 삭제 시 `403 Forbidden`이 반환된다.
- [ ]  이미 삭제된 매물을 다시 삭제 요청하면 `409 Conflict`가 반환된다.
- [ ]  인증 토큰 없이 호출하면 `401 Unauthorized`가 반환된다.
- [ ]  Swagger / Postman에서 테스트 가능하다.

---

## 참고 메모

- 실제 DB 레코드는 삭제하지 않는 소프트 딜리트 정책입니다. 연관된 Checklist/RiskSignal/DepositSafetyCheck 레코드도 함께 지우지 않습니다.
- 완전 삭제(hard delete)가 필요한 정책(예: 개인정보/법적 요건)이 생기면 별도 배치/관리자 API로 분리하는 것을 권장합니다.
