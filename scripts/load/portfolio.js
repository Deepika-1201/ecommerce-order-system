import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const api = __ENV.API || 'http://app:8080';
const identity = __ENV.KEYCLOAK || 'http://keycloak:8080';
const runId = __ENV.RUN_ID || `local-${Date.now()}`;
const workload = __ENV.WORKLOAD || 'browse';
const rate = positiveInteger('RATE', 10, 50);
const seconds = positiveInteger('SECONDS', 60, 300);
const vus = positiveInteger('VUS', 8, 32);
const buyers = positiveInteger('BUYERS', 24, 64);
const stock = positiveInteger('STOCK', 5, 64);
const unexpected = new Counter('unexpected_responses');
const throttled = new Counter('throttled_responses');
const accepted = new Counter('orders_accepted');
const held = new Counter('orders_payment_ready');
const rejected = new Counter('orders_stock_rejected');
const replayed = new Counter('idempotent_replays');
const unresolved = new Counter('orders_unresolved');
const placement = new Trend('placement_ms', true);
const replay = new Trend('replay_ms', true);
const settlement = new Trend('settlement_ms', true);
const elapsed = new Trend('scenario_elapsed_ms', true);

if (!['browse', 'contention'].includes(workload)) throw new Error('WORKLOAD must be browse or contention');
if (workload === 'contention' && stock >= buyers) throw new Error('Contention needs fewer stock units than buyers');

if (!/^http:\/\/(app|localhost|127\.0\.0\.1)(:\d+)?$/.test(api)
    || !/^http:\/\/(keycloak|localhost|127\.0\.0\.1)(:\d+)?$/.test(identity)) {
  throw new Error('This harness is restricted to the local portfolio stack');
}
if (!/^[A-Za-z0-9-]{1,40}$/.test(runId)) throw new Error('RUN_ID must be 1-40 letters, digits, or hyphens');

export const options = {
  scenarios: workload === 'browse' ? {
    browse: { executor: 'constant-arrival-rate', exec: 'browse', rate, timeUnit: '1s', duration: `${seconds}s`,
      preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '10s' },
  } : {
    contention: { executor: 'per-vu-iterations', exec: 'contend', vus: buyers, iterations: 1,
      maxDuration: '90s', gracefulStop: '10s' },
  },
  setupTimeout: '3m',
  teardownTimeout: '30s',
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'],
    unexpected_responses: ['count==0'],
    throttled_responses: ['count==0'],
    [`http_reqs{scenario:${workload}}`]: ['count>0'],
    [`http_req_failed{scenario:${workload}}`]: ['rate==0'],
    ...(workload === 'browse' ? {
      dropped_iterations: ['count==0'],
      'http_req_duration{scenario:browse}': ['p(95)<1000'],
    } : {
      orders_accepted: [`count==${buyers}`],
      orders_payment_ready: [`count==${stock}`],
      orders_stock_rejected: [`count==${buyers - stock}`],
      idempotent_replays: [`count==${buyers}`],
      orders_unresolved: ['count==0'],
      placement_ms: ['p(95)<2000'],
      settlement_ms: ['p(95)<30000'],
    }),
  },
};

export function setup() {
  unexpected.add(0);
  throttled.add(0);
  const admin = token('admin', 'admin-local-only');
  const categorySlug = `load-${runId.toLowerCase()}`;
  const category = apiJson('POST', '/v1/admin/catalog/categories', admin,
    { name: `Load ${runId}`, slug: categorySlug }, 201, 'setup_category');
  const product = apiJson('POST', '/v1/admin/catalog/products', admin,
    { title: `Load product ${runId}`, category_id: category.id, gst_category: 'STANDARD', options: [] },
    201, 'setup_product');
  const sku = `LOAD-${runId.toUpperCase()}`;
  apiJson('POST', `/v1/admin/catalog/products/${product.id}/variants`, admin,
    { sku, option_values: {}, price_paise: 59900 }, 201, 'setup_variant');
  apiJson('POST', `/v1/admin/catalog/products/${product.id}/activate`, admin, null, 200, 'setup_activate');
  const data = { productId: product.id, categorySlug, sku };
  if (workload === 'browse') return data;
  accepted.add(0);
  held.add(0);
  rejected.add(0);
  replayed.add(0);
  unresolved.add(0);
  const warehouse = token('meera', 'meera-local-only');
  apiJson('POST', `/v1/warehouse/stock/${sku}/receipts`, warehouse, { quantity: stock }, 200, 'setup_stock',
    { 'Idempotency-Key': `load-receipt-${runId}` });
  const customers = ['asha', 'ravi'].map(name => ({ bearer: token(name, `${name}-local-only`) }));
  for (const customer of customers) {
    const cart = apiJson('GET', '/v1/me/cart', customer.bearer, null, 200, 'setup_cart');
    for (const line of cart.lines) apiJson('DELETE', `/v1/me/cart/lines/${line.sku}`, customer.bearer,
      null, 200, 'setup_clear_cart');
    apiJson('DELETE', '/v1/me/cart/coupon', customer.bearer, null, 200, 'setup_clear_coupon');
    const addresses = apiJson('GET', '/v1/me/addresses', customer.bearer, null, 200, 'setup_addresses');
    let address = addresses.items.find(item => item.state_code === '29' && item.pin_code === '560038');
    if (!address) address = apiJson('POST', '/v1/me/addresses', customer.bearer,
      { recipient_name: 'Local load fixture', phone: '9876543210', line1: '1 Test Street', city: 'Bengaluru',
        state_code: '29', pin_code: '560038' }, 201, 'setup_address');
    customer.addressId = address.id;
    apiJson('PUT', `/v1/me/cart/lines/${sku}`, customer.bearer, { quantity: 1 }, 200, 'setup_cart_line');
  }
  data.buyers = [];
  for (let index = 0; index < buyers; index++) {
    const customer = customers[index % customers.length];
    const quote = apiJson('POST', '/v1/me/cart/quotes', customer.bearer, { delivery_state_code: '29' },
      201, 'setup_quote');
    data.buyers.push({ bearer: customer.bearer,
      body: { quote_id: quote.id, delivery_address_id: customer.addressId }, key: `load-order-${runId}-${index}` });
  }
  data.warehouse = warehouse;
  return data;
}

export function browse(data) {
  const listing = request('GET', `/v1/products?category=${data.categorySlug}&limit=20`, null, null, 200, 'catalog_list');
  check(listing, { 'catalogue contains fixture': response => response.status === 200
    && response.json().items.some(item => item.id === data.productId) });
  const detail = request('GET', `/v1/products/${data.productId}`, null, null, 200, 'product_detail');
  check(detail, { 'detail matches fixture': response => response.status === 200 && response.json().id === data.productId });
  elapsed.add(Date.now() - exec.scenario.startTime);
}

export function contend(data) {
  const buyer = data.buyers[exec.vu.idInTest - 1];
  const started = Date.now();
  const headers = { 'Idempotency-Key': buyer.key };
  const first = request('POST', '/v1/me/orders', buyer.bearer, buyer.body, 202, 'order_place', headers);
  placement.add(first.timings.duration);
  if (!check(first, { 'placement accepted': response => response.status === 202 })) return;
  accepted.add(1);
  const orderId = first.json().id;
  const repeated = request('POST', '/v1/me/orders', buyer.bearer, buyer.body, 202, 'order_replay', headers);
  replay.add(repeated.timings.duration);
  const identical = check(repeated, { 'replay is byte-identical': response => response.status === 202
    && response.body === first.body && response.json().id === orderId });
  if (identical) replayed.add(1);
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    const response = request('GET', `/v1/me/orders/${orderId}`, buyer.bearer, null, 200, 'order_poll');
    if (response.status !== 200) break;
    const order = response.json();
    if (order.status === 'AWAITING_PAYMENT' || order.status === 'REJECTED') {
      if (order.status === 'AWAITING_PAYMENT') held.add(1);
      else if (check(order, { 'rejection is for this SKU being out of stock': value => value.reason === 'OUT_OF_STOCK'
          && value.unavailable_sku === data.sku })) rejected.add(1);
      settlement.add(Date.now() - started);
      elapsed.add(Date.now() - exec.scenario.startTime);
      return;
    }
    sleep(0.2);
  }
  unresolved.add(1);
  check(false, { 'every accepted order reaches a bounded outcome': value => value });
  elapsed.add(Date.now() - exec.scenario.startTime);
}

export function teardown(data) {
  if (workload !== 'contention' || !data) return;
  const levels = apiJson('GET', `/v1/warehouse/stock/${data.sku}`, data.warehouse, null, 200, 'verify_stock');
  check(levels, { 'stock counters match the one-unit winners': value => value.on_hand === stock
    && value.reserved === stock && value.available === 0 });
}

export function handleSummary(data) {
  const report = {
    schema_version: 1,
    workload,
    run_id: runId,
    sku: `LOAD-${runId.toUpperCase()}`,
    configuration: workload === 'browse'
      ? { iteration_rate: rate, requests_per_iteration: 2, duration_seconds: seconds, vus }
      : { buyers, stock_units: stock, customer_accounts: 2, outcome_timeout_seconds: 60 },
    measured_duration_ms: data.metrics.scenario_elapsed_ms ? data.metrics.scenario_elapsed_ms.values.max : null,
    total_run_duration_ms: data.state.testRunDurationMs,
    metrics: data.metrics,
    checks: data.root_group,
  };
  return { [__ENV.SUMMARY || '/results/summary.json']: JSON.stringify(report, null, 2),
    stdout: `Portfolio ${workload} run ${runId} finished; inspect the report and thresholds.\n` };
}

function token(username, password) {
  const response = http.post(`${identity}/realms/ecommerce/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'ecommerce-cli', username, password },
    { timeout: '10s', tags: { name: 'setup_token' }, responseCallback: http.expectedStatuses(200) });
  if (response.status !== 200) fail(`Local identity setup failed: HTTP ${response.status}`);
  const value = response.json().access_token;
  if (!value) fail('Local identity setup returned no access token');
  return value;
}

function request(method, path, bearer, body, expected, name, extraHeaders = {}) {
  const headers = { 'Content-Type': 'application/json', ...extraHeaders };
  if (bearer) headers.Authorization = `Bearer ${bearer}`;
  const response = http.request(method, api + path, body === null ? null : JSON.stringify(body),
    { headers, timeout: '5s', tags: { name }, responseCallback: http.expectedStatuses(expected) });
  unexpected.add(response.status !== expected && response.status !== 429 ? 1 : 0);
  throttled.add(response.status === 429 ? 1 : 0);
  return response;
}

function apiJson(method, path, bearer, body, expected, name, headers = {}) {
  const response = request(method, path, bearer, body, expected, name, headers);
  if (response.status !== expected) fail(`${name} failed: HTTP ${response.status}`);
  return response.json();
}

function positiveInteger(name, fallback, maximum) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value < 1 || value > maximum) throw new Error(`${name} must be 1-${maximum}`);
  return value;
}