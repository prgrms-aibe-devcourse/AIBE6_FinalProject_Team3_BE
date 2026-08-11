import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './common/config.js';
import { login } from './common/auth.js';

// 스모크 테스트 단계: VU 1명, 30초만 - 스크립트 자체가 정상 동작하는지만 확인.
// 검증되면 stages를 늘려서(예: 0→20명 ramp-up, 5분 유지) baseline 측정으로 전환.
export const options = {
  vus: 1,
  duration: '30s',
};

const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;

// setup()은 테스트 시작 전 딱 한 번만 실행된다 - 로그인을 VU마다 반복하지 않고,
// 여기서 받은 쿠키를 이후 default() 안에서 재사용한다.
export function setup() {
  const res = login(TEST_EMAIL, TEST_PASSWORD);
  return { cookies: res.cookies };
}

function cookieHeader(cookies) {
  return Object.entries(cookies)
    .map(([name, jar]) => `${name}=${jar[0].value}`)
    .join('; ');
}

export default function (data) {
  const headers = { Cookie: cookieHeader(data.cookies) };

  // 1-1. 매물 목록 (검색/페이지네이션)
  const listRes = http.get(`${BASE_URL}/properties?page=0&size=20`, { headers });
  check(listRes, {
    '매물 목록 200': (r) => r.status === 200,
    '매물 목록 500ms 이내': (r) => r.timings.duration < 500,
  });

  // 1-2. 매물 상세 - 목록에서 첫 번째 매물 ID를 그대로 사용(하드코딩 방지)
  const properties = listRes.json('data.content');
  if (properties && properties.length > 0) {
    const propertyId = properties[0].propertyId;
    const detailRes = http.get(`${BASE_URL}/properties/${propertyId}`, { headers });
    check(detailRes, {
      '매물 상세 200': (r) => r.status === 200,
      '매물 상세 500ms 이내': (r) => r.timings.duration < 500,
    });
  }

  sleep(1);
}
