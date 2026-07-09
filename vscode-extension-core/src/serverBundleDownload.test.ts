import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { describe, test } from 'node:test';
import {
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
});
