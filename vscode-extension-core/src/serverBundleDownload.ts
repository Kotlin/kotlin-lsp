import { createHash } from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import http from 'node:http';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pipeline } from 'node:stream/promises';

export const SERVER_BUNDLE_METADATA_FILE = 'server-bundle.json';

export interface ServerBundleMetadata {
  url: string;
  sha256?: string;
  archiveName?: string;
}

export interface EnsureServerLauncherOptions {
  extensionPath: string;
  storagePath: string;
  log: (message: string) => void;
  progress?: (progress: ServerBundleProgress) => void;
}

export interface ServerBundleProgress {
  message: string;
  increment?: number;
}

const INSTALL_LOCK_RETRY_DELAY_MS = 200;
const INSTALL_LOCK_STALE_MS = 2 * 60 * 1000;
const INSTALL_LOCK_HEARTBEAT_MS = INSTALL_LOCK_STALE_MS / 4;
const INSTALL_LOCK_TIMEOUT_MS = INSTALL_LOCK_STALE_MS * 5;
const INSTALL_LOCK_OWNER_FILE_PREFIX = 'owner.';
const INSTALL_LOCK_OWNER_FILE_SUFFIX = '.json';
const DOWNLOAD_IDLE_TIMEOUT_MS = 2 * 60 * 1000;
const DOWNLOAD_PROGRESS_INCREMENT = 70;

export async function ensureServerLauncher({
  extensionPath,
  storagePath,
  log,
  progress,
}: EnsureServerLauncherOptions): Promise<string> {
  const bundledServerDir = path.join(extensionPath, 'server');
  const bundledLauncher = serverLauncherPath(bundledServerDir);
  if (fs.existsSync(bundledLauncher)) {
    return bundledLauncher;
  }

  const metadata = await readServerBundleMetadata(extensionPath);
  const downloadedServerDir = path.join(
    storagePath,
    'downloaded-server',
    serverBundleInstallDir(metadata),
  );
  const downloadedLauncher = serverLauncherPath(downloadedServerDir);
  if (!fs.existsSync(downloadedLauncher)) {
    await withInstallLock(
      `${downloadedServerDir}.lock`,
      log,
      () => fs.existsSync(downloadedLauncher),
      async () => {
        if (!fs.existsSync(downloadedLauncher)) {
          await downloadAndExtractServerBundle(
            metadata,
            downloadedServerDir,
            storagePath,
            log,
            progress,
          );
        }
      },
    );
  }

  if (!fs.existsSync(downloadedLauncher)) {
    throw new Error(`Downloaded language server is missing launcher: ${downloadedLauncher}`);
  }
  return downloadedLauncher;
}

export function serverLauncherPath(serverDir: string): string {
  const launcherName = os.platform() === 'win32' ? 'intellij-server.exe' : 'intellij-server';
  return path.join(serverDir, 'bin', launcherName);
}

export async function readServerBundleMetadata(
  extensionPath: string,
): Promise<ServerBundleMetadata> {
  const metadataPath = path.join(extensionPath, SERVER_BUNDLE_METADATA_FILE);
  let raw: string;
  try {
    raw = await fsp.readFile(metadataPath, 'utf8');
  } catch (e) {
    const detail = e instanceof Error ? e.message : String(e);
    throw new Error(
      `Bundled language server is missing and no download metadata was found at ${metadataPath}: ${detail}`,
    );
  }

  let metadata: Partial<ServerBundleMetadata>;
  try {
    metadata = JSON.parse(raw) as Partial<ServerBundleMetadata>;
  } catch (e) {
    const detail = e instanceof Error ? e.message : String(e);
    throw new Error(
      `Invalid language server download metadata: malformed JSON in ${metadataPath}: ${detail}`,
    );
  }
  if (typeof metadata.url !== 'string' || metadata.url.length === 0) {
    throw new Error(`Invalid language server download metadata: missing url in ${metadataPath}`);
  }
  if (metadata.sha256 !== undefined && !/^[0-9a-fA-F]{64}$/.test(metadata.sha256)) {
    throw new Error(`Invalid language server download metadata: bad sha256 in ${metadataPath}`);
  }
  if (
    metadata.archiveName !== undefined &&
    path.basename(metadata.archiveName) !== metadata.archiveName
  ) {
    throw new Error(
      `Invalid language server download metadata: bad archiveName in ${metadataPath}`,
    );
  }
  return {
    url: metadata.url,
    sha256: metadata.sha256,
    archiveName: metadata.archiveName,
  };
}

function serverBundleInstallDir(metadata: ServerBundleMetadata): string {
  if (metadata.sha256) return metadata.sha256;
  const archiveName = serverBundleArchiveName(metadata);
  return createHash('sha256').update(metadata.url).update('\0').update(archiveName).digest('hex');
}

function serverBundleArchiveName(metadata: ServerBundleMetadata): string {
  return (metadata.archiveName ?? path.basename(new URL(metadata.url).pathname)) || 'server-bundle';
}

async function downloadAndExtractServerBundle(
  metadata: ServerBundleMetadata,
  serverDir: string,
  storagePath: string,
  log: (message: string) => void,
  progress?: (progress: ServerBundleProgress) => void,
): Promise<void> {
  await fsp.mkdir(storagePath, { recursive: true });
  const tmpRoot = await fsp.mkdtemp(path.join(storagePath, 'server-download-'));
  const archivePath = path.join(tmpRoot, serverBundleArchiveName(metadata));
  const extractDir = path.join(tmpRoot, 'server');

  try {
    log(`Downloading language server from ${metadata.url}`);
    progress?.({ message: 'Downloading language server' });
    let reportedDownloadIncrement = 0;
    await downloadFile(metadata.url, archivePath, (downloadedBytes, totalBytes) => {
      if (totalBytes === undefined) return;
      const downloadRatio = Math.min(1, downloadedBytes / totalBytes);
      const downloadPercentage = Math.floor(downloadRatio * 100);
      const nextIncrement = Math.floor(downloadRatio * DOWNLOAD_PROGRESS_INCREMENT);
      const increment = Math.max(0, nextIncrement - reportedDownloadIncrement);
      if (increment > 0) {
        reportedDownloadIncrement += increment;
        progress?.({
          message: `Downloading language server (${downloadPercentage}%)`,
          increment,
        });
      }
    });
    if (reportedDownloadIncrement < DOWNLOAD_PROGRESS_INCREMENT) {
      progress?.({
        message: 'Downloaded language server',
        increment: DOWNLOAD_PROGRESS_INCREMENT - reportedDownloadIncrement,
      });
    }
    if (metadata.sha256) {
      progress?.({ message: 'Verifying language server download', increment: 10 });
      const actual = await sha256(archivePath);
      if (actual.toLowerCase() !== metadata.sha256.toLowerCase()) {
        throw new Error(
          `Language server download checksum mismatch: expected ${metadata.sha256}, got ${actual}`,
        );
      }
    }

    log('Extracting language server');
    progress?.({ message: 'Extracting language server', increment: 15 });
    await extractServerBundle(archivePath, extractDir);
    progress?.({ message: 'Installing language server', increment: 5 });
    await publishExtractedServerBundle(extractDir, serverDir);
  } finally {
    await fsp.rm(tmpRoot, { recursive: true, force: true });
  }
}

async function publishExtractedServerBundle(extractDir: string, serverDir: string): Promise<void> {
  await fsp.mkdir(path.dirname(serverDir), { recursive: true });
  try {
    await fsp.rename(extractDir, serverDir);
  } catch (e) {
    if (!isAlreadyExistsError(e)) throw e;
    if (fs.existsSync(serverLauncherPath(serverDir))) {
      return;
    }
    await quarantineIncompleteServerDir(serverDir);
    await fsp.rename(extractDir, serverDir);
  }
}

async function quarantineIncompleteServerDir(serverDir: string): Promise<void> {
  const staleServerDir = `${serverDir}.incomplete.${process.pid}.${Date.now()}`;
  try {
    await fsp.rename(serverDir, staleServerDir);
  } catch (e) {
    if (isNoSuchFileError(e)) return;
    throw e;
  }
  await fsp.rm(staleServerDir, { recursive: true, force: true });
}

async function downloadFile(
  url: string,
  destination: string,
  onProgress?: (downloadedBytes: number, totalBytes?: number) => void,
  redirectsLeft = 5,
): Promise<void> {
  await fsp.mkdir(path.dirname(destination), { recursive: true });
  const parsed = new URL(url);
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new Error(`Unsupported language server download URL protocol: ${parsed.protocol}`);
  }
  const client = parsed.protocol === 'https:' ? https : http;

  await new Promise<void>((resolve, reject) => {
    const request = client.get(parsed, (response) => {
      const status = response.statusCode ?? 0;
      response.setTimeout(DOWNLOAD_IDLE_TIMEOUT_MS, () => {
        request.destroy(new Error(`Timed out downloading ${url}: no data received`));
      });
      if (status >= 300 && status < 400 && response.headers.location) {
        response.resume();
        if (redirectsLeft === 0) {
          reject(new Error(`Too many redirects while downloading ${url}`));
          return;
        }
        const nextUrl = new URL(response.headers.location, parsed).toString();
        downloadFile(nextUrl, destination, onProgress, redirectsLeft - 1).then(resolve, reject);
        return;
      }

      if (status !== 200) {
        response.resume();
        reject(new Error(`Failed to download ${url}: HTTP ${status}`));
        return;
      }

      const output = fs.createWriteStream(destination);
      const totalBytes = contentLength(response.headers['content-length']);
      let downloadedBytes = 0;
      response.on('data', (chunk: Buffer) => {
        downloadedBytes += chunk.length;
        onProgress?.(downloadedBytes, totalBytes);
      });
      pipeline(response, output).then(resolve, reject);
    });
    request.setTimeout(DOWNLOAD_IDLE_TIMEOUT_MS, () => {
      request.destroy(new Error(`Timed out downloading ${url}: no response received`));
    });
    request.on('error', reject);
  });
}

function contentLength(value: string | string[] | undefined): number | undefined {
  const raw = Array.isArray(value) ? value[0] : value;
  if (raw === undefined) return undefined;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

async function withInstallLock<T>(
  lockDir: string,
  log: (message: string) => void,
  isComplete: () => boolean,
  action: () => Promise<T>,
): Promise<T | undefined> {
  await fsp.mkdir(path.dirname(lockDir), { recursive: true });
  let deadline = Date.now() + INSTALL_LOCK_TIMEOUT_MS;
  let loggedWait = false;
  const token = `${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  while (true) {
    if (isComplete()) {
      return undefined;
    }
    try {
      await fsp.mkdir(lockDir);
      await writeInstallLockOwner(lockDir, token);
      break;
    } catch (e) {
      if (!isAlreadyExistsError(e)) throw e;
      const lockFresh = await isInstallLockFresh(lockDir);
      if (!lockFresh && (await removeStaleInstallLock(lockDir, log))) {
        continue;
      }
      if (isComplete()) {
        return undefined;
      }
      if (Date.now() >= deadline) {
        if (lockFresh) {
          deadline = Date.now() + INSTALL_LOCK_TIMEOUT_MS;
          continue;
        }
        throw new Error(`Timed out waiting for language server install lock: ${lockDir}`);
      }
      if (!loggedWait) {
        loggedWait = true;
        log(`Waiting for language server install lock: ${lockDir}`);
      }
      await delay(INSTALL_LOCK_RETRY_DELAY_MS);
    }
  }

  const heartbeat = setInterval(() => {
    void touchInstallLockOwner(lockDir, token).catch(() => {
      // Best-effort heartbeat. If this owner file is gone, this process no longer owns lockDir.
    });
  }, INSTALL_LOCK_HEARTBEAT_MS);
  heartbeat.unref();
  try {
    return await action();
  } finally {
    clearInterval(heartbeat);
    await releaseOwnInstallLock(lockDir, token);
  }
}

async function writeInstallLockOwner(lockDir: string, token: string): Promise<void> {
  const now = Date.now();
  const owner = {
    token,
    pid: process.pid,
    createdAt: now,
    updatedAt: now,
  };
  await fsp.writeFile(installLockOwnerPath(lockDir, token), JSON.stringify(owner, null, 2), 'utf8');
}

async function touchInstallLockOwner(lockDir: string, token: string): Promise<void> {
  const now = new Date();
  await fsp.utimes(installLockOwnerPath(lockDir, token), now, now);
}

async function releaseOwnInstallLock(lockDir: string, token: string): Promise<void> {
  await fsp.rm(installLockOwnerPath(lockDir, token), { force: true });
  try {
    // rmdir removes only an empty directory, so it cannot delete a newer owner's lock contents.
    await fsp.rmdir(lockDir);
  } catch (e) {
    if (!isNoSuchFileError(e)) throw e;
  }
}

async function removeStaleInstallLock(
  lockDir: string,
  log: (message: string) => void,
): Promise<boolean> {
  const stat = await installLockStat(lockDir);

  if (stat && Date.now() - stat.mtimeMs > INSTALL_LOCK_STALE_MS) {
    log(`Removing stale language server install lock: ${lockDir}`);
    await quarantineStaleInstallLock(lockDir);
    return true;
  }
  return false;
}

async function isInstallLockFresh(lockDir: string): Promise<boolean> {
  const stat = await installLockStat(lockDir);
  return stat !== undefined && Date.now() - stat.mtimeMs <= INSTALL_LOCK_STALE_MS;
}

async function installLockStat(lockDir: string): Promise<fs.Stats | undefined> {
  const ownerPath = await findInstallLockOwnerPath(lockDir);
  return (
    (ownerPath ? await fsp.stat(ownerPath).catch(() => undefined) : undefined) ??
    (await fsp.stat(lockDir).catch(() => undefined))
  );
}

async function quarantineStaleInstallLock(lockDir: string): Promise<void> {
  const staleLockDir = `${lockDir}.stale.${process.pid}.${Date.now()}`;
  try {
    await fsp.rename(lockDir, staleLockDir);
  } catch (e) {
    if (isNoSuchFileError(e)) return;
    throw e;
  }
  await fsp.rm(staleLockDir, { recursive: true, force: true });
}

function installLockOwnerPath(lockDir: string, token: string): string {
  return path.join(
    lockDir,
    `${INSTALL_LOCK_OWNER_FILE_PREFIX}${token}${INSTALL_LOCK_OWNER_FILE_SUFFIX}`,
  );
}

async function findInstallLockOwnerPath(lockDir: string): Promise<string | undefined> {
  const entries = await fsp.readdir(lockDir).catch(() => undefined);
  const ownerFiles = entries?.filter(
    (entry) =>
      entry.startsWith(INSTALL_LOCK_OWNER_FILE_PREFIX) &&
      entry.endsWith(INSTALL_LOCK_OWNER_FILE_SUFFIX),
  );
  let newestOwner: { path: string; mtimeMs: number } | undefined;
  for (const ownerFile of ownerFiles ?? []) {
    const ownerPath = path.join(lockDir, ownerFile);
    const stat = await fsp.stat(ownerPath).catch(() => undefined);
    if (!stat?.isFile()) continue;
    if (newestOwner === undefined || stat.mtimeMs > newestOwner.mtimeMs) {
      newestOwner = { path: ownerPath, mtimeMs: stat.mtimeMs };
    }
  }
  return newestOwner?.path;
}

function isAlreadyExistsError(e: unknown): boolean {
  return (
    typeof e === 'object' &&
    e !== null &&
    'code' in e &&
    (e.code === 'EEXIST' || e.code === 'ENOTEMPTY')
  );
}

function isNoSuchFileError(e: unknown): boolean {
  return typeof e === 'object' && e !== null && 'code' in e && e.code === 'ENOENT';
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function sha256(file: string): Promise<string> {
  const hash = createHash('sha256');
  const input = fs.createReadStream(file);
  for await (const chunk of input) {
    hash.update(chunk);
  }
  return hash.digest('hex');
}

async function extractServerBundle(archivePath: string, serverDir: string): Promise<void> {
  const lower = archivePath.toLowerCase();
  const tmpDir = `${serverDir}.tmp`;

  await fsp.rm(serverDir, { recursive: true, force: true });
  await fsp.rm(tmpDir, { recursive: true, force: true });

  // Keep archive layout handling in sync with community/kotlin-vscode/unpack-server.mjs.
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) {
    await fsp.mkdir(serverDir, { recursive: true });
    await run(tarCommand(), ['-xzf', archivePath, '--strip-components=1', '-C', serverDir]);
  } else if (lower.endsWith('.zip')) {
    await fsp.mkdir(serverDir, { recursive: true });
    await run(tarCommand(), ['-xf', archivePath, '-C', serverDir]);
  } else if (lower.endsWith('.sit')) {
    await fsp.mkdir(tmpDir, { recursive: true });
    await run(tarCommand(), ['-xf', archivePath, '-C', tmpDir]);
    await promoteSitContentsToServer(tmpDir, serverDir);
    await fsp.rm(tmpDir, { recursive: true, force: true });
  } else {
    throw new Error(`Unsupported language server archive type: ${archivePath}`);
  }

  const libDir = path.join(serverDir, 'lib');
  const stat = await fsp.stat(libDir).catch(() => undefined);
  if (!stat?.isDirectory()) {
    throw new Error(`Unpacked language server is missing lib directory: ${libDir}`);
  }
}

function tarCommand(): string {
  return process.platform === 'win32'
    ? path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe')
    : 'tar';
}

async function promoteSitContentsToServer(tmpDir: string, serverDir: string): Promise<void> {
  const entries = await fsp.readdir(tmpDir);
  if (entries.length === 1) {
    const singleEntry = path.join(tmpDir, entries[0]);
    const stat = await fsp.stat(singleEntry);
    if (stat.isDirectory()) {
      await fsp.rename(singleEntry, serverDir);
      return;
    }
  }

  await fsp.mkdir(serverDir, { recursive: true });
  for (const name of entries) {
    await fsp.rename(path.join(tmpDir, name), path.join(serverDir, name));
  }
}

function run(command: string, args: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk: string | Buffer) => {
      stdout = appendProcessOutput(stdout, chunk.toString());
    });
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk: string | Buffer) => {
      stderr = appendProcessOutput(stderr, chunk.toString());
    });
    child.on('error', reject);
    child.on('exit', (code) =>
      code === 0
        ? resolve()
        : reject(
            new Error(
              `${command} ${args.join(' ')} exited with code ${code}. stdout: ${stdout} stderr: ${stderr}`,
            ),
          ),
    );
  });
}

function appendProcessOutput(previousOutput: string, chunk: string): string {
  return (previousOutput + chunk).slice(-20_000);
}
