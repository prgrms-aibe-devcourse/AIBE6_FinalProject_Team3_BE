import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './config.js';

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
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(res, { '로그인 성공(200)': (r) => r.status === 200 });
  return res;
}
