import { strict as assert } from 'node:assert';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { computePortFilePath, readPortFile } from './portFile';

const WORKSPACE_ROOT = '/home/user/my-workspace';
const EXPECTED_FILE = 'e22d7af6977edd2e.port';

test('computePortFilePath names the file by the workspace-root hash under the version', () => {
  const expected = path.join(os.tmpdir(), 'intellij-lsp-servers', '0.3.1', EXPECTED_FILE);
  assert.equal(computePortFilePath({ workspaceRoot: WORKSPACE_ROOT, version: '0.3.1' }), expected);
});

test('computePortFilePath separates extension versions', () => {
  const previous = computePortFilePath({ workspaceRoot: WORKSPACE_ROOT, version: '0.3.0' });
  const current = computePortFilePath({ workspaceRoot: WORKSPACE_ROOT, version: '0.3.1' });
  assert.notEqual(previous, current);
});

test('readPortFile returns the published port', () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'port-file-test-'));
  try {
    const file = path.join(dir, 'server.port');
    writeFileSync(file, '54321\n');
    assert.equal(readPortFile(file), 54321);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('readPortFile rejects a missing, empty, or out-of-range file', () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'port-file-test-'));
  try {
    assert.equal(readPortFile(path.join(dir, 'absent.port')), undefined);

    const empty = path.join(dir, 'empty.port');
    writeFileSync(empty, '   ');
    assert.equal(readPortFile(empty), undefined);

    const tooBig = path.join(dir, 'big.port');
    writeFileSync(tooBig, '70000');
    assert.equal(readPortFile(tooBig), undefined);

    const notNumber = path.join(dir, 'text.port');
    writeFileSync(notNumber, 'not-a-port');
    assert.equal(readPortFile(notNumber), undefined);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
