import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import os from 'node:os';

const output = process.argv[2];
assert.ok(output, 'Usage: node scripts/load/environment.mjs output.json');
const command = (program, args) => execFileSync(program, args, { encoding: 'utf8', timeout: 20000 }).trim();
const services = ['app', 'postgres', 'kafka', 'keycloak', 's3proxy', 'mailpit', 'prometheus', 'grafana'];
const optional = new Set(['prometheus', 'grafana']);
const configured = new Set(command('docker', ['compose', 'config', '--services']).split('\n'));
const containers = {};
for (const service of services) {
  if (!configured.has(service) && optional.has(service)) continue;
  const id = command('docker', ['compose', 'ps', '-q', service]);
  if (!id && optional.has(service)) continue;
  assert.ok(id, `Missing local service ${service}`);
  const [container] = JSON.parse(command('docker', ['inspect', id]));
  assert.equal(container.State.Running, true, `${service} is not running`);
  containers[service] = container;
}
const app = containers.app;
const environment = Object.fromEntries(app.Config.Env.map(entry => {
  const separator = entry.indexOf('=');
  return [entry.slice(0, separator), entry.slice(separator + 1)];
}));
assert.ok(!environment.ECOM_PAYMENTS_GATEWAY_BASE_URL, 'This local harness does not benchmark the real payment gateway');
assert.ok(!environment.SPRING_APPLICATION_JSON && !environment.SPRING_CONFIG_LOCATION
  && !environment.SPRING_CONFIG_ADDITIONAL_LOCATION && !environment.SPRING_PROFILES_ACTIVE,
  'External Spring configuration requires a separately reviewed benchmark');
assert.ok(!(environment.JAVA_TOOL_OPTIONS ?? '').match(/-D(?:spring|ecom)\./),
  'Hidden Java system-property overrides are not supported by this harness');
const quotas = {
  enabled: environment.ECOM_SECURITY_RATE_LIMITS_ENABLED ?? 'true',
  ip_per_minute: Number(environment.ECOM_SECURITY_RATE_LIMITS_IP_PER_MINUTE ?? 3000),
  principal_writes_per_minute: Number(environment.ECOM_SECURITY_RATE_LIMITS_PRINCIPAL_WRITES_PER_MINUTE ?? 120),
  webhook_ip_per_minute: Number(environment.ECOM_SECURITY_RATE_LIMITS_WEBHOOK_IP_PER_MINUTE ?? 6000),
  instance_per_minute: Number(environment.ECOM_SECURITY_RATE_LIMITS_INSTANCE_PER_MINUTE ?? 20000),
  max_keys: Number(environment.ECOM_SECURITY_RATE_LIMITS_MAX_KEYS ?? 10000),
};
assert.equal(quotas.enabled, 'true', 'Portfolio load evidence keeps application rate limits enabled');
const docker = JSON.parse(command('docker', ['info', '--format', '{{json .}}']));
const java = spawnSync('docker', ['exec', app.Id, 'java', '-version'], { encoding: 'utf8', timeout: 20000 });
assert.equal(java.status, 0, 'Could not identify the runtime JVM');
const javaVersion = java.stderr.split('\n').filter(line => /^(openjdk|java version|OpenJDK|Eclipse)/.test(line));
assert.ok(javaVersion.length > 0, 'Missing JVM version evidence');
const database = JSON.parse(command('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'ecommerce',
  '-d', 'ecommerce', '-qAt', '-c',
  "SELECT json_build_object('version', current_setting('server_version'), 'catalog_products', (SELECT count(*) FROM catalog.products), 'orders', (SELECT count(*) FROM ordering.orders))"]));
const stats = command('docker', ['stats', '--no-stream', '--format', '{{json .}}', ...Object.values(containers).map(value => value.Id)])
  .split('\n').map(line => JSON.parse(line)).map(value => ({ name: value.Name, cpu_percent: value.CPUPerc,
    memory: value.MemUsage, network_io: value.NetIO, block_io: value.BlockIO }));
const report = {
  schema_version: 1,
  captured_at: new Date().toISOString(),
  source_commit: command('git', ['rev-parse', 'HEAD']),
  worktree_dirty: command('git', ['status', '--porcelain']).length > 0,
  harness_sha256: Object.fromEntries(['portfolio.js', 'audit.sql', 'report.mjs', 'environment.mjs'].map(name =>
    [name, createHash('sha256').update(readFileSync(`scripts/load/${name}`)).digest('hex')])),
  host: { platform: os.platform(), architecture: os.arch(), release: os.release(),
    cpu_model: os.cpus()[0]?.model, logical_cpus: os.cpus().length, memory_bytes: os.totalmem(), load_average: os.loadavg() },
  docker: { version: docker.ServerVersion, operating_system: docker.OperatingSystem, architecture: docker.Architecture,
    cpus: docker.NCPU, memory_bytes: docker.MemTotal },
  java: javaVersion,
  database,
  application: { roles: environment.ECOM_ROLES ?? 'api,worker',
    events_enabled: environment.ECOM_EVENTS_ENABLED ?? 'false',
    payment_adapter: 'local fake', database_pool_limit: Number(environment.DB_POOL_SIZE ?? 20),
    dispatcher_concurrency: Number(environment.ECOM_MESSAGING_DISPATCHER_CONCURRENCY ?? 4),
    task_concurrency: Number(environment.ECOM_TASKS_CONCURRENCY ?? 4),
    java_memory_options: (environment.JAVA_TOOL_OPTIONS ?? '').split(/\s+/)
      .filter(option => /^-XX:MaxRAMPercentage=[0-9.]+$|^-XX:\+ExitOnOutOfMemoryError$|^-Xm[sx][0-9]+[mMgG]$/.test(option)),
    quotas },
  containers: Object.fromEntries(Object.entries(containers).map(([name, value]) => [name, {
    image: value.Config.Image, image_id: value.Image, memory_limit_bytes: value.HostConfig.Memory,
    nano_cpus: value.HostConfig.NanoCpus, user: value.Config.User,
  }])),
  resource_snapshot: stats,
  other_running_containers: command('docker', ['ps', '-q']).split('\n')
    .filter(id => id && !Object.values(containers).some(container => container.Id.startsWith(id))).length,
};
writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(`Captured ${Object.keys(containers).length} local services; JVM and rate-limit settings recorded without secrets`);