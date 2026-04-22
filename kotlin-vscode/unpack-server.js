#!/usr/bin/env node

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const zip = process.env.LSP_ZIP_PATH;
if (!zip) {
  console.error('Error: LSP_ZIP_PATH is not set');
  process.exit(1);
}
if (!fs.existsSync(zip)) {
  console.error(`Error: LSP zip not found: ${zip}`);
  process.exit(1);
}

const serverDir = path.resolve(__dirname, 'server');
const tmpDir = path.resolve(__dirname, 'server.tmp');
const isWin = process.platform === 'win32';
const lower = zip.toLowerCase();

// On Windows pin to bsdtar shipped in System32 — Git Bash's GNU tar appears
// first on PATH in many dev shells and rejects `C:\…` archive paths with
// "Cannot connect to C: resolve failed".
const tarCmd = isWin
  ? path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe')
  : 'tar';

function run(cmd, args) {
  const r = spawnSync(cmd, args, { stdio: 'inherit' });
  if (r.error) {
    console.error(r.error.message);
    process.exit(1);
  }
  if (r.status !== 0) process.exit(r.status ?? 1);
}

fs.rmSync(serverDir, { recursive: true, force: true });
fs.rmSync(tmpDir, { recursive: true, force: true });

if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) {
  fs.mkdirSync(serverDir, { recursive: true });
  run(tarCmd, ['-xzf', zip, '--strip-components=1', '-C', serverDir]);
} else if (lower.endsWith('.zip')) {
  fs.mkdirSync(serverDir, { recursive: true });
  if (isWin) run(tarCmd, ['-xf', zip, '-C', serverDir]);
  else run('unzip', ['-q', '-o', '--', zip, '-d', serverDir]);
} else if (lower.endsWith('.sit')) {
  fs.mkdirSync(tmpDir, { recursive: true });
  if (isWin) run(tarCmd, ['-xf', zip, '-C', tmpDir]);
  else run('unzip', ['-q', '-o', '--', zip, '-d', tmpDir]);

  // Promote tmp contents into server/. If the archive has a single top-level
  // directory, strip that level (matches the original `mv server.tmp/* server`
  // behavior after the preceding `rmdir server`).
  const entries = fs.readdirSync(tmpDir);
  if (entries.length === 1 && fs.statSync(path.join(tmpDir, entries[0])).isDirectory()) {
    fs.renameSync(path.join(tmpDir, entries[0]), serverDir);
  } else {
    fs.mkdirSync(serverDir, { recursive: true });
    for (const name of entries) {
      fs.renameSync(path.join(tmpDir, name), path.join(serverDir, name));
    }
  }
  fs.rmSync(tmpDir, { recursive: true, force: true });
} else {
  console.error(`Unsupported archive type: ${zip}`);
  process.exit(1);
}
