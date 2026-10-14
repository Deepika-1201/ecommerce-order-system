import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const requiredPackages = [
  'org.apache.tomcat.embed:tomcat-embed-core',
  'tools.jackson.core:jackson-databind',
  'com.bucket4j:bucket4j_jdk17-core',
];

function inspect(report, mode) {
  assert.equal(report.SchemaVersion, 2, 'Unsupported or missing Trivy report schema');
  assert.ok(['jar', 'image', 'secrets'].includes(mode), 'Choose jar, image, or secrets');
  const results = report.Results ?? [];
  assert.ok(Array.isArray(results), 'Invalid scanner results');
  if (mode === 'secrets') {
    const secrets = results.flatMap(result => result.Secrets ?? []);
    assert.equal(secrets.length, 0, `Secret-pattern findings: ${secrets.length}; review privately, never upload their contents`);
    return { mode, secretFindings: 0 };
  }
  const javaPackages = results.filter(result => result.Type === 'jar').flatMap(result => result.Packages ?? []);
  for (const name of requiredPackages) {
    assert.ok(javaPackages.some(pkg => pkg.Name === name), `Scanner did not inventory shipped package ${name}`);
  }
  const osPackages = results.filter(result => result.Class === 'os-pkgs').flatMap(result => result.Packages ?? []);
  if (mode === 'image') assert.ok(osPackages.length > 0, 'Image scan did not inventory operating-system packages');
  const findings = results.flatMap(result => result.Vulnerabilities ?? [])
    .filter(finding => ['HIGH', 'CRITICAL'].includes(finding.Severity));
  const fixable = findings.filter(finding => (finding.FixedVersion ?? '').trim().length > 0);
  assert.equal(fixable.length, 0, `Fixable HIGH/CRITICAL findings: ${fixable.map(finding => `${finding.PkgName}: ${finding.VulnerabilityID}`).join(', ')}`);
  return { mode, javaPackages: javaPackages.length, osPackages: osPackages.length, fixableHighCritical: 0,
    unfixedHighCritical: findings.length };
}

if (process.argv[2] === '--self-test') {
  const clean = { SchemaVersion: 2, Results: [{ Type: 'jar', Packages: requiredPackages.map(Name => ({ Name })) }] };
  assert.equal(inspect(clean, 'jar').fixableHighCritical, 0);
  assert.throws(() => inspect({ SchemaVersion: 2, Results: [] }, 'jar'), /did not inventory/);
  assert.throws(() => inspect(clean, 'image'), /operating-system/);
  const image = structuredClone(clean);
  image.Results.push({ Class: 'os-pkgs', Packages: [{ Name: 'libc6' }] });
  assert.equal(inspect(image, 'image').osPackages, 1);
  for (const severity of ['HIGH', 'CRITICAL']) {
    const vulnerable = structuredClone(clean);
    vulnerable.Results[0].Vulnerabilities = [{ PkgName: 'test-package', VulnerabilityID: 'TEST-FIX', Severity: severity, FixedVersion: '2' }];
    assert.throws(() => inspect(vulnerable, 'jar'), /Fixable HIGH\/CRITICAL/);
    vulnerable.Results[0].Vulnerabilities[0].FixedVersion = '';
    assert.equal(inspect(vulnerable, 'jar').unfixedHighCritical, 1);
  }
  assert.throws(() => inspect({ SchemaVersion: 2, Results: [{ Secrets: [{ Match: 'private-value' }] }] }, 'secrets'), error =>
    error.message.includes('Secret-pattern findings: 1') && !error.message.includes('private-value'));
  assert.equal(inspect({ SchemaVersion: 2 }, 'secrets').secretFindings, 0);
  assert.throws(() => inspect({}, 'jar'), /schema/);
  console.log('Security report policy checks passed');
} else {
  const [mode, file] = process.argv.slice(2);
  assert.ok(file, 'Usage: node scripts/check-security-report.mjs <jar|image|secrets> <report.json>');
  console.log(JSON.stringify(inspect(JSON.parse(readFileSync(file, 'utf8')), mode)));
}