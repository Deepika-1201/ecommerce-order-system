import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { stripVTControlCharacters } from 'node:util';

const documents = ['README.md', 'docs/portfolio-guide.md', 'docs/portfolio-milestone.md',
  'docs/portfolio-runbook.md', 'docs/portfolio-security.md', 'docs/portfolio-performance.md'];
let links = 0;
for (const document of documents) {
  const text = readFileSync(document, 'utf8');
  for (const match of text.matchAll(/\]\(([^)#]+)(?:#([^)]*))?\)/g)) {
    if (/^https?:/.test(match[1])) continue;
    const target = path.resolve(path.dirname(document), decodeURIComponent(match[1]));
    assert.ok(existsSync(target), `${document}: missing ${match[1]}`);
    if (match[2] && target.endsWith('.md')) {
      const headings = [...readFileSync(target, 'utf8').matchAll(/^#{1,6}\s+(.+)$/gm)]
        .map(heading => heading[1].toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').trim().replace(/\s/g, '-'));
      assert.ok(headings.includes(match[2]), `${document}: missing heading ${match[1]}#${match[2]}`);
    }
    links++;
  }
}

const directory = 'docs/recordings/2026-10-15';
const recording = readFileSync(`${directory}/walkthrough.cast`);
const metadata = JSON.parse(readFileSync(`${directory}/fresh-checkout.json`, 'utf8'));
assert.match(metadata.source_commit, /^[a-f0-9]{40}$/);
assert.equal(metadata.checkout_clean_before_and_after, true);
assert.equal(metadata.data_volumes_initially_absent, true);
assert.deepEqual(metadata.before, { catalog_products: 0, orders: 0, notifications: 0 });
assert.deepEqual(metadata.after, { catalog_products: 2, orders: 3, notifications: 6 });
assert.equal(createHash('sha256').update(recording).digest('hex'), metadata.recording.sha256);
const events = recording.toString('utf8').trim().split('\n').map(line => JSON.parse(line));
assert.equal(events[0].version, 2);
assert.ok(events.length > 1, 'Empty terminal recording');
let previous = 0;
for (const event of events.slice(1)) {
  assert.equal(event.length, 3);
  assert.ok(Number.isFinite(event[0]) && event[0] >= previous, 'Invalid recording time');
  assert.equal(event[1], 'o', 'Only stdout should be recorded, never typed credentials');
  assert.equal(typeof event[2], 'string');
  previous = event[0];
}
assert.equal(previous, metadata.recording.duration_seconds);
const output = stripVTControlCharacters(events.slice(1).map(event => event[2]).join(''))
  .replace(/\r\n/g, '\n').replace(/\r/g, '');
assert.equal(readFileSync(`${directory}/transcript.txt`, 'utf8'), output, 'Transcript differs from actual recording');
assert.ok(output.includes('Demo passed. Order:') && output.includes('Observability demo passed.'));
assert.ok(!output.includes('FAILED:'), 'Recorded demonstration failed');
assert.ok(!/Bearer\s+[A-Za-z0-9]|"access_token"|eyJ[A-Za-z0-9_-]{25,}\./.test(output), 'Credential-like recording content');
console.log(`Verified ${links} local reviewer links and the ${previous.toFixed(1)}-second real walkthrough recording`);