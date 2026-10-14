import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';

function verify(summary, audit) {
  assert.equal(summary.schema_version, 1, 'Unsupported load report');
  assert.ok(['browse', 'contention'].includes(summary.workload), 'Unknown workload');
  assert.match(summary.run_id, /^[A-Za-z0-9-]{1,40}$/);
  assert.equal(summary.sku, `LOAD-${summary.run_id.toUpperCase()}`);
  assert.ok(Number.isFinite(summary.measured_duration_ms) && summary.measured_duration_ms > 0, 'No measured scenario duration');
  const metric = (name, field) => {
    const value = summary.metrics?.[name]?.values?.[field];
    assert.ok(Number.isFinite(value), `Missing/invalid metric ${name}.${field}`);
    return value;
  };
  for (const [name, value] of Object.entries(summary.metrics ?? {})) {
    for (const [rule, threshold] of Object.entries(value.thresholds ?? {})) {
      assert.equal(threshold.ok, true, `Failed threshold ${name}: ${rule}`);
    }
  }
  assert.equal(metric('checks', 'rate'), 1, 'A functional check failed');
  assert.equal(metric('unexpected_responses', 'count'), 0, 'Unexpected HTTP responses');
  assert.equal(metric('throttled_responses', 'count'), 0, 'Throttled traffic is not a valid capacity result');
  assert.equal(metric(`http_req_failed{scenario:${summary.workload}}`, 'rate'), 0, 'Measured HTTP errors');
  const requests = metric(`http_reqs{scenario:${summary.workload}}`, 'count');
  assert.ok(requests > 0, 'No measured requests');
  const result = { schema_version: 1, run_id: summary.run_id, workload: summary.workload, passed: true,
    configuration: summary.configuration, scenario_duration_seconds: summary.measured_duration_ms / 1000,
    measured_http_requests: requests, measured_http_requests_per_second: requests / (summary.measured_duration_ms / 1000),
    unexpected_responses: 0, throttled_responses: 0 };
  const latency = name => Object.fromEntries(['med', 'p(95)', 'p(99)', 'max'].map(field => [field, metric(name, field)]));
  if (summary.workload === 'browse') {
    for (const field of ['iteration_rate', 'duration_seconds', 'vus']) {
      assert.ok(Number.isInteger(summary.configuration[field]) && summary.configuration[field] > 0,
        `Invalid browse configuration ${field}`);
    }
    assert.equal(metric('dropped_iterations', 'count'), 0, 'Generator dropped scheduled iterations');
    assert.ok(requests >= summary.configuration.iteration_rate * summary.configuration.duration_seconds * 2,
      'Too few catalogue requests');
    assert.ok(summary.measured_duration_ms >= summary.configuration.duration_seconds * 1000,
      'Scenario ended before the configured duration');
    result.http_latency_ms = latency('http_req_duration{scenario:browse}');
    assert.ok(result.http_latency_ms['p(95)'] < 1000, 'Catalogue p95 exceeds the local evidence budget');
  } else {
    const { buyers, stock_units: stock } = summary.configuration;
    assert.ok(Number.isInteger(buyers) && Number.isInteger(stock) && stock > 0 && stock < buyers);
    for (const [name, expected] of Object.entries({ orders_accepted: buyers, orders_payment_ready: stock,
      orders_stock_rejected: buyers - stock, idempotent_replays: buyers, orders_unresolved: 0 })) {
      assert.equal(metric(name, 'count'), expected, `Incorrect ${name}`);
    }
    assert.ok(audit, 'A contention run requires the database audit');
    assert.equal(audit.schema_version, 1);
    assert.equal(audit.run_id, summary.run_id);
    assert.equal(audit.sku, summary.sku);
    for (const [name, expected] of Object.entries({ stock_rows: 1, on_hand: stock, reserved: stock, available: 0,
      movement_units: stock, orders: buyers, payment_ready: stock, stock_rejected: buyers - stock,
      active_reservations: stock, reserved_units: stock, replay_rows: buyers, distinct_replay_orders: buyers,
      counters_valid: true, reservation_links_valid: true, replay_links_valid: true })) {
      assert.equal(audit[name], expected, `Failed database invariant ${name}`);
    }
    result.placement_latency_ms = latency('placement_ms');
    result.replay_latency_ms = latency('replay_ms');
    result.outcome_latency_ms = latency('settlement_ms');
    assert.ok(result.placement_latency_ms['p(95)'] < 2000, 'Placement p95 exceeds the local evidence budget');
    assert.ok(result.outcome_latency_ms['p(95)'] < 30000, 'Outcome p95 exceeds the bounded wait budget');
    result.outcomes = { payment_ready: stock, stock_rejected: buyers - stock, idempotent_replays: buyers };
    result.audit = audit;
  }
  return result;
}

function selfTest() {
  const metric = values => ({ values });
  const summary = { schema_version: 1, workload: 'contention', run_id: 'fixture', sku: 'LOAD-FIXTURE',
    measured_duration_ms: 1000, configuration: { buyers: 8, stock_units: 2 }, metrics: {
      checks: metric({ rate: 1 }), unexpected_responses: metric({ count: 0 }), throttled_responses: metric({ count: 0 }),
      'http_req_failed{scenario:contention}': metric({ rate: 0 }), 'http_reqs{scenario:contention}': metric({ count: 24 }),
      orders_accepted: metric({ count: 8 }), orders_payment_ready: metric({ count: 2 }),
      orders_stock_rejected: metric({ count: 6 }), idempotent_replays: metric({ count: 8 }), orders_unresolved: metric({ count: 0 }),
      placement_ms: metric({ med: 10, 'p(95)': 20, 'p(99)': 25, max: 30 }),
      replay_ms: metric({ med: 5, 'p(95)': 10, 'p(99)': 15, max: 20 }),
      settlement_ms: metric({ med: 100, 'p(95)': 200, 'p(99)': 250, max: 300 }),
    } };
  const audit = { schema_version: 1, run_id: 'fixture', sku: 'LOAD-FIXTURE', stock_rows: 1, on_hand: 2,
    reserved: 2, available: 0, movement_units: 2, orders: 8, payment_ready: 2, stock_rejected: 6,
    active_reservations: 2, reserved_units: 2, replay_rows: 8, distinct_replay_orders: 8,
    counters_valid: true, reservation_links_valid: true, replay_links_valid: true };
  assert.equal(verify(summary, audit).passed, true);
  assert.throws(() => verify(summary), /requires the database audit/);
  for (const field of ['available', 'reserved_units', 'movement_units', 'orders', 'distinct_replay_orders']) {
    assert.throws(() => verify(summary, { ...audit, [field]: audit[field] + 1 }), /database invariant/);
  }
  for (const field of ['counters_valid', 'reservation_links_valid', 'replay_links_valid']) {
    assert.throws(() => verify(summary, { ...audit, [field]: false }), /database invariant/);
  }
  for (const name of ['throttled_responses', 'unexpected_responses', 'orders_unresolved']) {
    const bad = structuredClone(summary);
    bad.metrics[name].values.count = 1;
    assert.throws(() => verify(bad, audit));
  }
  const missing = structuredClone(summary);
  delete missing.metrics.placement_ms;
  assert.throws(() => verify(missing, audit), /Missing\/invalid metric/);
  assert.throws(() => verify({ ...summary, measured_duration_ms: 0 }, audit), /duration/);
  const failed = structuredClone(summary);
  failed.metrics.checks.thresholds = { 'rate==1': { ok: false } };
  assert.throws(() => verify(failed, audit), /Failed threshold/);
  const browse = { ...structuredClone(summary), workload: 'browse', measured_duration_ms: 5010,
    configuration: { iteration_rate: 2, duration_seconds: 5, vus: 2 } };
  browse.metrics['http_req_failed{scenario:browse}'] = metric({ rate: 0 });
  browse.metrics['http_reqs{scenario:browse}'] = metric({ count: 22 });
  browse.metrics['http_req_duration{scenario:browse}'] = metric({ med: 5, 'p(95)': 10, 'p(99)': 15, max: 20 });
  browse.metrics.dropped_iterations = metric({ count: 0 });
  assert.equal(verify(browse).passed, true);
  assert.throws(() => verify({ ...browse, measured_duration_ms: 4000 }), /ended before/);
  assert.throws(() => verify({ ...browse, configuration: {} }), /Invalid browse configuration/);
  browse.metrics.dropped_iterations.values.count = 1;
  assert.throws(() => verify(browse), /dropped scheduled iterations/);
  browse.metrics.dropped_iterations.values.count = 0;
  browse.metrics['http_reqs{scenario:browse}'].values.count = 1;
  assert.throws(() => verify(browse), /Too few catalogue requests/);
  console.log('Load evidence policy checks passed');
}

if (process.argv[2] === '--self-test') selfTest();
else {
  const [summaryPath, auditPath, outputPath] = process.argv.slice(2);
  assert.ok(summaryPath, 'Usage: node scripts/load/report.mjs summary.json [audit.json|-] [verified.json]');
  const summary = JSON.parse(readFileSync(summaryPath, 'utf8'));
  const audit = auditPath && auditPath !== '-' ? JSON.parse(readFileSync(auditPath, 'utf8')) : undefined;
  const result = verify(summary, audit);
  if (outputPath) writeFileSync(outputPath, JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify(result, null, 2));
}