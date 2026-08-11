// 실행 시 -e BASE_URL=https://... 로 오버라이드. 안 주면 로컬 기본값 사용.
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// CsrfHeaderFilter가 상태변경 요청(POST/PUT/PATCH/DELETE)에 X-Requested-With 헤더 또는
// 허용된 Origin 헤더 중 하나를 요구한다 - 브라우저는 cross-origin 요청에 Origin을 자동으로
// 붙이지만 k6는 아무것도 안 붙이므로, 값 자체는 검사하지 않는(존재 여부만 보는) 이 헤더를
// 모든 POST/PUT/PATCH/DELETE 요청에 직접 실어줘야 403 CSRF_HEADER_MISSING을 피할 수 있다.
export const CSRF_HEADERS = { 'X-Requested-With': 'k6-load-test' };
