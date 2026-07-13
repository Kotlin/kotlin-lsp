import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { describe, test } from 'node:test';
import {
  downloadFile,
  ensureServerLauncher,
  readServerBundleMetadata,
  serverLauncherPath,
} from './serverBundleDownload';

describe('server bundle download metadata', () => {
  test('uses bundled server launcher when it is already present', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    try {
      const extensionPath = path.join(root, 'extension');
      const storagePath = path.join(root, 'storage');
      const launcherPath = serverLauncherPath(path.join(extensionPath, 'server'));
      await fs.mkdir(path.dirname(launcherPath), { recursive: true });
      await fs.writeFile(launcherPath, '');

      assert.equal(
        await ensureServerLauncher({ extensionPath, storagePath, log: () => {} }),
        launcherPath,
      );
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('uses the newest cached server without metadata during extension development', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    try {
      const extensionPath = path.join(root, 'extension');
      const storagePath = path.join(root, 'storage');
      const olderLauncher = await createCachedLauncher(storagePath, 'a');
      const newerLauncher = await createCachedLauncher(storagePath, 'b');
      const olderTime = new Date('2026-01-01T00:00:00Z');
      const newerTime = new Date('2026-02-01T00:00:00Z');
      await fs.utimes(path.dirname(path.dirname(olderLauncher)), olderTime, olderTime);
      await fs.utimes(path.dirname(path.dirname(newerLauncher)), newerTime, newerTime);
      const logs: string[] = [];

      assert.equal(
        await ensureServerLauncher({
          extensionPath,
          storagePath,
          log: (message) => logs.push(message),
          allowCachedServerWithoutMetadata: true,
        }),
        newerLauncher,
      );
      assert.ok(logs.some((message) => message.includes(newerLauncher)));
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('requires metadata outside extension development when only a cached server exists', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    try {
      const extensionPath = path.join(root, 'extension');
      const storagePath = path.join(root, 'storage');
      await createCachedLauncher(storagePath, 'a');

      await assert.rejects(
        ensureServerLauncher({ extensionPath, storagePath, log: () => {} }),
        /no download metadata was found/,
      );
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('ignores incomplete cached servers during extension development', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    try {
      const extensionPath = path.join(root, 'extension');
      const storagePath = path.join(root, 'storage');
      const incompleteLauncher = serverLauncherPath(
        path.join(storagePath, 'downloaded-server', 'a'.repeat(64)),
      );
      await fs.mkdir(path.dirname(incompleteLauncher), { recursive: true });

      await assert.rejects(
        ensureServerLauncher({
          extensionPath,
          storagePath,
          log: () => {},
          allowCachedServerWithoutMetadata: true,
        }),
        /no download metadata was found/,
      );
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('reads valid metadata', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    try {
      await fs.writeFile(
        path.join(root, 'server-bundle.json'),
        JSON.stringify({
          url: 'https://download.example.test/server.tar.gz',
          archiveName: 'server.tar.gz',
          sha256: 'a'.repeat(64),
        }),
      );

      assert.deepEqual(await readServerBundleMetadata(root), {
        url: 'https://download.example.test/server.tar.gz',
        archiveName: 'server.tar.gz',
        sha256: 'a'.repeat(64),
      });
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('rejects archive names with paths', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    try {
      await fs.writeFile(
        path.join(root, 'server-bundle.json'),
        JSON.stringify({
          url: 'https://download.example.test/server.tar.gz',
          archiveName: '../server.tar.gz',
        }),
      );

      await assert.rejects(readServerBundleMetadata(root), /bad archiveName/);
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('reports invalid metadata JSON with context', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    try {
      await fs.writeFile(path.join(root, 'server-bundle.json'), '{');

      await assert.rejects(
        readServerBundleMetadata(root),
        /Invalid language server download metadata/,
      );
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('retries immediately after a download failure releases the install lock', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    try {
      const extensionPath = path.join(root, 'extension');
      const storagePath = path.join(root, 'storage');
      const sha256 = '0'.repeat(64);
      await fs.mkdir(extensionPath, { recursive: true });
      await fs.writeFile(
        path.join(extensionPath, 'server-bundle.json'),
        JSON.stringify({
          url: 'file:///server.tar.gz',
          archiveName: 'server.tar.gz',
          sha256,
        }),
      );

      const ensure = () => ensureServerLauncher({ extensionPath, storagePath, log: () => {} });
      await assert.rejects(ensure, /Unsupported language server download URL protocol/);
      await assert.rejects(fs.stat(path.join(storagePath, 'downloaded-server', `${sha256}.lock`)), {
        code: 'ENOENT',
      });
      await assert.rejects(ensure, /Unsupported language server download URL protocol/);
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('recovers immediately when the previous extension host left its install lock', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    try {
      const extensionPath = path.join(root, 'extension');
      const storagePath = path.join(root, 'storage');
      const sha256 = '0'.repeat(64);
      const lockPath = path.join(storagePath, 'downloaded-server', `${sha256}.lock`);
      await fs.mkdir(extensionPath, { recursive: true });
      await fs.writeFile(
        path.join(extensionPath, 'server-bundle.json'),
        JSON.stringify({
          url: 'file:///server.tar.gz',
          archiveName: 'server.tar.gz',
          sha256,
        }),
      );
      await fs.mkdir(lockPath, { recursive: true });
      await fs.writeFile(
        path.join(lockPath, 'owner.abandoned.json'),
        JSON.stringify({ pid: 2_147_483_647 }),
      );
      const logs: string[] = [];

      await assert.rejects(
        ensureServerLauncher({ extensionPath, storagePath, log: (message) => logs.push(message) }),
        /Unsupported language server download URL protocol/,
      );

      assert.ok(logs.some((message) => message.includes('Removing abandoned')));
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('resumes a partial download with an HTTP range request', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    const destination = path.join(root, 'server.tar.gz');
    await fs.writeFile(destination, 'abc');
    const server = http.createServer((request, response) => {
      assert.equal(request.headers.range, 'bytes=3-');
      response.writeHead(206, {
        'Content-Length': '3',
        'Content-Range': 'bytes 3-5/6',
      });
      response.end('def');
    });
    try {
      const url = await listen(server);
      await downloadFile(url, destination);

      assert.equal(await fs.readFile(destination, 'utf8'), 'abcdef');
    } finally {
      await close(server);
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('restarts a partial download when the server ignores the range request', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    const destination = path.join(root, 'server.tar.gz');
    await fs.writeFile(destination, 'stale');
    const server = http.createServer((request, response) => {
      assert.equal(request.headers.range, 'bytes=5-');
      response.writeHead(200, { 'Content-Length': '5' });
      response.end('fresh');
    });
    try {
      const url = await listen(server);
      await downloadFile(url, destination);

      assert.equal(await fs.readFile(destination, 'utf8'), 'fresh');
    } finally {
      await close(server);
      await fs.rm(root, { recursive: true, force: true });
    }
  });

  test('keeps a completed download when the range starts at the end of the file', async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'server-bundle-'));
    const destination = path.join(root, 'server.tar.gz');
    await fs.writeFile(destination, 'complete');
    const server = http.createServer((request, response) => {
      assert.equal(request.headers.range, 'bytes=8-');
      response.writeHead(416, { 'Content-Range': 'bytes */8' });
      response.end();
    });
    try {
      const url = await listen(server);
      await downloadFile(url, destination);

      assert.equal(await fs.readFile(destination, 'utf8'), 'complete');
    } finally {
      await close(server);
      await fs.rm(root, { recursive: true, force: true });
    }
  });
});

async function listen(server: http.Server): Promise<string> {
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  if (address === null || typeof address === 'string')
    throw new Error('Expected TCP server address');
  return `http://127.0.0.1:${address.port}/server.tar.gz`;
}

async function createCachedLauncher(storagePath: string, hashCharacter: string): Promise<string> {
  const launcherPath = serverLauncherPath(
    path.join(storagePath, 'downloaded-server', hashCharacter.repeat(64)),
  );
  await fs.mkdir(path.dirname(launcherPath), { recursive: true });
  await fs.writeFile(launcherPath, '');
  return launcherPath;
}

async function close(server: http.Server): Promise<void> {
  if (!server.listening) return;
  await new Promise<void>((resolve, reject) =>
    server.close((error) => (error === undefined ? resolve() : reject(error))),
  );
}
