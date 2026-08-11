import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, CSRF_HEADERS } from './common/config.js';
import { login, extractAuthCookies, authCookieHeader } from './common/auth.js';

const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;

// 시나리오 3개를 시간대를 나눠서 실행한다(동시에 겹치면 서로의 세션을 깨뜨림):
//
// 1) concurrentRefresh(0s 시작) - setup()에서 만든 단 하나의 refresh token을 5개 VU가
//    정확히 같은 시점에 동시 호출한다. RefreshTokenService.rotate()가 Lua 스크립트로
//    원자적으로 처리되므로, 정확히 1건만 200(rotate 성공)이고 나머지는 이미 무효화된
//    토큰이라 401이어야 정상 - "유저당 세션 1개" 정책이 동시 요청에서도 깨지지 않는지 확인.
// 2) jwtFilterOverhead(0s 시작, concurrentRefresh와 병행 가능) - refresh token이 아니라
//    access token만 쓰므로 위 refresh 경쟁과 무관하다. JwtAuthenticationFilter가 매 요청마다
//    Redis 블랙리스트 조회를 하는 비용이 부하 상황에서 얼마나 드는지 관찰.
// 3) concurrentLogin(30s 시작) - 반드시 concurrentRefresh가 끝난 뒤에 시작해야 한다.
//    같은 계정으로 재로그인하면 "재로그인 시 이전 refresh token 즉시 무효화" 정책 때문에
//    concurrentRefresh가 테스트 중이던 토큰을 중간에 깨뜨려버릴 수 있다.
export const options = {
  scenarios: {
    concurrentRefresh: {
      executor: 'per-vu-iterations',
      exec: 'refreshScenario',
      vus: 5,
      iterations: 1,
      startTime: '0s',
      maxDuration: '30s',
    },
    jwtFilterOverhead: {
      executor: 'constant-vus',
      exec: 'meScenario',
      vus: 10,
      duration: '30s',
      startTime: '0s',
    },
    concurrentLogin: {
      executor: 'constant-vus',
      exec: 'loginScenario',
      vus: 5,
      duration: '1m',
      startTime: '30s',
    },
  },
};

// 전체 테스트 시작 전 딱 한 번 실행된다(시나리오별이 아니라 test-run 전체 기준).
export function setup() {
  const res = login(TEST_EMAIL, TEST_PASSWORD);
  return { authCookies: extractAuthCookies(res) };
}

// 2-2. 동시 refresh 경쟁
export function refreshScenario(data) {
  const headers = { Cookie: authCookieHeader(data.authCookies), ...CSRF_HEADERS };
  const res = http.post(`${BASE_URL}/auth/refresh`, null, { headers });
  check(res, {
    'refresh 200(승자) 또는 401(경쟁 패배)': (r) => r.status === 200 || r.status === 401,
    'refresh 500이 아님(서버 에러 없음)': (r) => r.status < 500,
  });
}

// 2-3. JWT 필터 오버헤드(access token만 사용, /auth/me로 가장 가벼운 인증 경로 반복 호출)
export function meScenario(data) {
  const headers = { Cookie: authCookieHeader(data.authCookies) };
  const res = http.get(`${BASE_URL}/auth/me`, { headers });
  check(res, {
    '/auth/me 200': (r) => r.status === 200,
    '/auth/me 300ms 이내': (r) => r.timings.duration < 300,
  });
  sleep(0.5);
}

// 2-1. 동시 로그인 부하(BCrypt 해시 비교 비용이 주 관심사)
export function loginScenario() {
  const res = login(TEST_EMAIL, TEST_PASSWORD);
  check(res, {
    '로그인 1s 이내': (r) => r.timings.duration < 1000,
  });
  sleep(1);
}
