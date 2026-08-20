import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './common/config.js';
import { login, extractAuthCookies, authCookieHeader } from './common/auth.js';

// spike test: 평소(10명) 트래픽에서 순간적으로 500명까지 튀었다가(예: 이벤트/공유로 갑자기
// 몰리는 상황) 다시 평소 수준으로 돌아왔을 때, (1) 스파이크 구간에서 얼마나 버티는지와
// (2) 스파이크가 끝난 뒤 평소 수준 응답시간으로 잘 회복되는지를 함께 확인한다.
// stress test와 달리 램프업 구간이 극도로 짧다(10초) - "서서히 늘어나는 부하"가 아니라
// "갑자기 튀는 부하"를 재현하는 게 목적. 200명 스파이크는 CPU/GC가 잠깐 튀었다가 바로
// 회복되는 걸 확인했음(1차 테스트) - 어디까지 순간적으로 버티는지 보려고 500명으로 올림.
export const options = {
  stages: [
    { duration: '10s', target: 10 },  // 평소 수준 워밍업
    { duration: '30s', target: 10 },  // 평소 수준 유지(baseline 응답시간 확보)
    { duration: '10s', target: 500 }, // 스파이크
    { duration: '1m', target: 500 },  // 스파이크 유지
    { duration: '10s', target: 10 },  // 스파이크 종료, 평소 수준으로 급감
    { duration: '1m', target: 10 },   // 회복 여부 관찰(평소 응답시간으로 돌아오는지)
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    // 스파이크 구간 포함 전체 평균 기준이라 넉넉하게 잡음 - 실제로는 구간별로 나눠서
    // (회복 구간만 따로) 확인하는 게 더 정확하니, 결과 확인 시 시계열로 구간을 나눠서 봐야 한다.
    http_req_failed: ['rate<0.1'],
  },
};

const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;

export function setup() {
  const res = login(TEST_EMAIL, TEST_PASSWORD);
  return { authCookies: extractAuthCookies(res) };
}

export default function (data) {
  const headers = { Cookie: authCookieHeader(data.authCookies) };

  const listRes = http.get(`${BASE_URL}/properties?page=0&size=20`, { headers });
  check(listRes, { '매물 목록 200': (r) => r.status === 200 });

  const properties = listRes.json('data.content');
  if (properties && properties.length > 0) {
    const propertyId = properties[0].propertyId;
    const detailRes = http.get(`${BASE_URL}/properties/${propertyId}`, { headers });
    check(detailRes, { '매물 상세 200': (r) => r.status === 200 });
  }

  const meRes = http.get(`${BASE_URL}/auth/me`, { headers });
  check(meRes, { '내 정보 조회 200': (r) => r.status === 200 });

  sleep(1);
}
