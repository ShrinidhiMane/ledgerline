// k6 load test: open accounts, then fire concurrent payments at the API.
//   docker run --rm -i --network host grafana/k6 run - < loadtest/payments.js
// Thresholds make the run FAIL if p95 latency or the error rate regress.
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const PAYMENTS = __ENV.PAYMENTS_URL || 'http://localhost:8081';
const LEDGER = __ENV.LEDGER_URL || 'http://localhost:8082';
const ACCOUNTS = Number(__ENV.ACCOUNTS || 20);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 200),        // payments per second
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<250', 'p(99)<500'],
    checks: ['rate>0.99'],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function setup() {
  const ids = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const res = http.post(`${LEDGER}/api/v1/accounts`,
      JSON.stringify({ ownerName: `load-${i}`, currency: 'USD', openingBalanceMinor: 100000000 }),
      { headers: JSON_HEADERS });
    check(res, { 'account opened': (r) => r.status === 201 });
    ids.push(res.json('id'));
  }
  return { ids };
}

export default function (data) {
  const ids = data.ids;
  const from = ids[Math.floor(Math.random() * ids.length)];
  let to = ids[Math.floor(Math.random() * ids.length)];
  if (to === from) to = ids[(ids.indexOf(from) + 1) % ids.length];

  const res = http.post(`${PAYMENTS}/api/v1/payments`,
    JSON.stringify({ payerAccountId: from, payeeAccountId: to, amountMinor: 1 + Math.floor(Math.random() * 5000), currency: 'USD' }),
    { headers: { ...JSON_HEADERS, 'Idempotency-Key': uuidv4(), 'X-Client-Id': `vu-${__VU}` } });

  check(res, { 'payment accepted (202)': (r) => r.status === 202 });
}
