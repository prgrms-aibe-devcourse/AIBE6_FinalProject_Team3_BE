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
 * 데이터를 전달할 때 거치는 직렬화 과정(Go 구조체를 거쳐 재직렬화됨)에서 각 쿠키 객체의
 * 프로퍼티 키가 소문자(name/value/...)에서 대문자(Name/Value/...)로 바뀐다 - 객체 구조 자체는
 * 남아있지만 기존 코드가 쓰던 소문자 키로는 더 이상 접근이 안 돼 전부 undefined가 되고, 이후
 * 요청엔 쿠키가 전혀 안 실려가는 문제가 있었다(로컬 재현으로 확인됨). 원시 문자열은 이 직렬화를
 * 안전하게 통과하므로, login() 직후(같은 함수 실행 컨텍스트) 여기서 미리 뽑아둔다.
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
