import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, CSRF_HEADERS } from './config.js';

/**
 * 이메일/비밀번호로 로그인해서 이 VU의 쿠키 jar에 access_token/refresh_token을 심는다.
 * 로그인 응답은 httpOnly 쿠키로 토큰을 내려준다(바디엔 유저 정보만) - k6는 브라우저가 아니라
 * httpOnly 여부와 무관하게 Set-Cookie를 그대로 저장/재전송하므로, 이후 같은 VU의 요청들은
 * 별도 처리 없이 인증된 상태로 나간다.
 */
export function login(email, password) {
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json', ...CSRF_HEADERS } }
  );

  check(res, { '로그인 성공(200)': (r) => r.status === 200 });
  return res;
}

/**
 * 로그인 응답(res.cookies)에서 access/refresh 토큰 값만 순수 문자열로 뽑아낸다.
 * res.cookies를 setup()의 리턴값으로 그대로 넘기면, k6가 setup() → 각 VU의 default()로
 * 데이터를 전달할 때 거치는 직렬화 과정에서 이 중첩 객체 구조가 유지되지 않아 사실상 빈 값이
 * 되고, 이후 요청엔 쿠키가 전혀 안 실려가는 문제가 있었다(회귀 테스트로 확인됨). 원시 문자열은
 * 이 직렬화를 안전하게 통과하므로, login() 직후(같은 함수 실행 컨텍스트) 여기서 미리 뽑아둔다.
 */
export function extractAuthCookies(res) {
  const cookies = res.cookies || {};
  return {
    accessToken: cookies.access_token ? cookies.access_token[0].value : undefined,
    refreshToken: cookies.refresh_token ? cookies.refresh_token[0].value : undefined,
  };
}

export function authCookieHeader(authCookies) {
  if (!authCookies || !authCookies.accessToken) {
    console.error('[auth] access_token을 찾을 수 없습니다 - 로그인 응답에 Set-Cookie가 없었을 수 있습니다.');
  }
  return `access_token=${authCookies.accessToken}; refresh_token=${authCookies.refreshToken}`;
}
