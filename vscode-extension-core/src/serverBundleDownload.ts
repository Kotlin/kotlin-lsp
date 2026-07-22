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
  version: string;
  sha256?: string;
  archiveName?: string;
}

export interface EnsureServerLauncherOptions {
  extensionPath: string;
  serverRoot: string;
  log: (message: string) => void;
  progress?: (progress: ServerBundleProgress) => void;
  signal?: AbortSignal;
  allowCachedServerWithoutMetadata?: boolean;
}

export interface ServerBundleProgress {
  phase: ServerBundlePhase;
  message: string;
  increment?: number;
}

export type ServerBundlePhase =
  | 'waiting'
  | 'downloading'
  | 'verifying'
  | 'extracting'
  | 'installing';

export class ServerBundleChecksumError extends Error {
  constructor(expected: string, actual: string) {
    super(`Language server download checksum mismatch: expected ${expected}, got ${actual}`);
  }
}

interface DownloadFileOptions {
  onProgress?: (downloadedBytes: number, totalBytes?: number) => void;
  resume?: boolean;
  redirectsLeft?: number;
  signal?: AbortSignal;
}

export function serverBundleStoragePath(
  packageName: string,
  platform: NodeJS.Platform = process.platform,
  env: NodeJS.ProcessEnv = process.env,
  homeDir: string = os.homedir(),
): string {
  const platformPath = platform === 'win32' ? path.win32 : path.posix;
  let applicationDataRoot: string;
  if (platform === 'darwin') {
    applicationDataRoot = platformPath.join(homeDir, 'Library', 'Application Support');
  } else if (platform === 'win32') {
    applicationDataRoot = env.LOCALAPPDATA ?? platformPath.join(homeDir, 'AppData', 'Local');
  } else {
    applicationDataRoot = env.XDG_DATA_HOME ?? platformPath.join(homeDir, '.local', 'share');
  }
  return platformPath.join(
    applicationDataRoot,
    'JetBrains',
    'IntelliJServer',
    'servers',
    packageName,
  );
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
  serverRoot,
  log,
  progress,
  signal,
  allowCachedServerWithoutMetadata = false,
}: EnsureServerLauncherOptions): Promise<string> {
  const bundledServerDir = path.join(extensionPath, 'server');
  const bundledLauncher = serverLauncherPath(bundledServerDir);
  if (fs.existsSync(bundledLauncher)) {
    return bundledLauncher;
  }
  throwIfAborted(signal);

  const metadataPath = path.join(extensionPath, SERVER_BUNDLE_METADATA_FILE);
  if (allowCachedServerWithoutMetadata && !fs.existsSync(metadataPath)) {
    const cachedLauncher = await findCachedServerLauncher(serverRoot);
    if (cachedLauncher !== undefined) {
      log(`Using cached language server for extension development: ${cachedLauncher}`);
      return cachedLauncher;
    }
  }

  const metadata = await readServerBundleMetadata(extensionPath);
  const downloadedServerDir = path.join(serverRoot, metadata.version);
  const downloadedLauncher = serverLauncherPath(downloadedServerDir);
  if (!fs.existsSync(downloadedLauncher)) {
    await withInstallLock(
      `${downloadedServerDir}.lock`,
      log,
      () => fs.existsSync(downloadedLauncher),
      () =>
        progress?.({
          phase: 'waiting',
          message: 'Waiting for language server setup in another window',
        }),
      async () => {
        throwIfAborted(signal);
        if (!fs.existsSync(downloadedLauncher)) {
          await downloadAndExtractServerBundle(
            metadata,
            downloadedServerDir,
            serverRoot,
            log,
            progress,
            signal,
          );
        }
      },
      signal,
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

async function findCachedServerLauncher(serverRoot: string): Promise<string | undefined> {
  const entries = await fsp.readdir(serverRoot, { withFileTypes: true }).catch(() => []);
  const candidates: Array<{ launcherPath: string; mtimeMs: number }> = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const serverDir = path.join(serverRoot, entry.name);
    const launcherPath = serverLauncherPath(serverDir);
    if (!fs.existsSync(launcherPath)) continue;
    const stat = await fsp.stat(serverDir).catch(() => undefined);
    if (stat !== undefined) {
      candidates.push({ launcherPath, mtimeMs: stat.mtimeMs });
    }
  }
  candidates.sort(
    (left, right) =>
      right.mtimeMs - left.mtimeMs || left.launcherPath.localeCompare(right.launcherPath),
  );
  return candidates[0]?.launcherPath;
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
  if (typeof metadata.version !== 'string' || metadata.version.length === 0) {
    throw new Error(
      `Invalid language server download metadata: missing version in ${metadataPath}`,
    );
  }
  if (!/^[0-9A-Za-z][0-9A-Za-z._-]*$/.test(metadata.version)) {
    throw new Error(`Invalid language server download metadata: bad version in ${metadataPath}`);
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
    version: metadata.version,
    sha256: metadata.sha256,
    archiveName: metadata.archiveName,
  };
}

export async function discardServerBundleDownload(
  extensionPath: string,
  serverRoot: string,
  log: (message: string) => void = () => {},
): Promise<void> {
  await removeServerBundleFiles(extensionPath, serverRoot, false, log);
}

export async function removeDownloadedServerBundle(
  extensionPath: string,
  serverRoot: string,
  log: (message: string) => void = () => {},
): Promise<void> {
  await removeServerBundleFiles(extensionPath, serverRoot, true, log);
}

async function removeServerBundleFiles(
  extensionPath: string,
  serverRoot: string,
  removeInstalledServer: boolean,
  log: (message: string) => void,
): Promise<void> {
  const metadata = await readServerBundleMetadata(extensionPath);
  const serverDir = path.join(serverRoot, metadata.version);
  const downloadRoot = path.join(serverRoot, 'server-downloads', metadata.version);
  await withInstallLock(
    `${serverDir}.lock`,
    log,
    () => false,
    () => {},
    async () => {
      await fsp.rm(downloadRoot, { recursive: true, force: true });
      if (removeInstalledServer) {
        await fsp.rm(serverDir, { recursive: true, force: true });
      }
    },
  );
}

function serverBundleArchiveName(metadata: ServerBundleMetadata): string {
  return (metadata.archiveName ?? path.basename(new URL(metadata.url).pathname)) || 'server-bundle';
}

async function downloadAndExtractServerBundle(
  metadata: ServerBundleMetadata,
  serverDir: string,
  serverRoot: string,
  log: (message: string) => void,
  progress?: (progress: ServerBundleProgress) => void,
  signal?: AbortSignal,
): Promise<void> {
  throwIfAborted(signal);
  await fsp.mkdir(serverRoot, { recursive: true });
  const downloadRoot = path.join(serverRoot, 'server-downloads', metadata.version);
  const archivePath = path.join(downloadRoot, serverBundleArchiveName(metadata));
  await fsp.mkdir(downloadRoot, { recursive: true });
  const tmpRoot = await fsp.mkdtemp(path.join(downloadRoot, 'server-download-'));
  const extractDir = path.join(tmpRoot, 'server');

  try {
    const existingArchiveBytes = metadata.sha256
      ? ((await fsp.stat(archivePath).catch(() => undefined))?.size ?? 0)
      : 0;
    const downloadAction = existingArchiveBytes > 0 ? 'Resuming' : 'Downloading';
    log(
      existingArchiveBytes > 0
        ? `Resuming language server download from ${existingArchiveBytes} bytes: ${metadata.url}`
        : `Downloading language server from ${metadata.url}`,
    );
    progress?.({ phase: 'downloading', message: `${downloadAction} language server` });
    let reportedDownloadIncrement = 0;
    await downloadFile(metadata.url, archivePath, {
      onProgress: (downloadedBytes, totalBytes) => {
        if (totalBytes === undefined) return;
        const downloadRatio = Math.min(1, downloadedBytes / totalBytes);
        const downloadPercentage = Math.floor(downloadRatio * 100);
        const nextIncrement = Math.floor(downloadRatio * DOWNLOAD_PROGRESS_INCREMENT);
        const increment = Math.max(0, nextIncrement - reportedDownloadIncrement);
        if (increment > 0) {
          reportedDownloadIncrement += increment;
          progress?.({
            phase: 'downloading',
            message: `${downloadAction} language server (${downloadPercentage}%)`,
            increment,
          });
        }
      },
      resume: metadata.sha256 !== undefined,
      signal,
    });
    if (reportedDownloadIncrement < DOWNLOAD_PROGRESS_INCREMENT) {
      progress?.({
        phase: 'downloading',
        message: 'Downloaded language server',
        increment: DOWNLOAD_PROGRESS_INCREMENT - reportedDownloadIncrement,
      });
    }
    throwIfAborted(signal);
    if (metadata.sha256) {
      progress?.({
        phase: 'verifying',
        message: 'Verifying language server download',
        increment: 10,
      });
      const actual = await sha256(archivePath);
      if (actual.toLowerCase() !== metadata.sha256.toLowerCase()) {
        await fsp.rm(archivePath, { force: true });
        throw new ServerBundleChecksumError(metadata.sha256, actual);
      }
    }

    log(`Extracting language server to ${serverDir}`);
    progress?.({ phase: 'extracting', message: 'Extracting language server', increment: 15 });
    await extractServerBundle(archivePath, extractDir, signal);
    progress?.({ phase: 'installing', message: 'Installing language server', increment: 5 });
    await publishExtractedServerBundle(extractDir, serverDir);
    await fsp.rm(downloadRoot, { recursive: true, force: true });
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

export async function downloadFile(
  url: string,
  destination: string,
  { onProgress, resume = true, redirectsLeft = 5, signal }: DownloadFileOptions = {},
): Promise<void> {
  throwIfAborted(signal);
  await fsp.mkdir(path.dirname(destination), { recursive: true });
  const parsed = new URL(url);
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new Error(`Unsupported language server download URL protocol: ${parsed.protocol}`);
  }
  const client = parsed.protocol === 'https:' ? https : http;
  const existingBytes = resume
    ? ((await fsp.stat(destination).catch(() => undefined))?.size ?? 0)
    : 0;
  const headers = existingBytes > 0 ? { Range: `bytes=${existingBytes}-` } : undefined;

  await new Promise<void>((resolve, reject) => {
    const request = client.get(parsed, { headers }, (response) => {
      const status = response.statusCode ?? 0;
      const restartDownload = () => {
        response.resume();
        fsp
          .rm(destination, { force: true })
          .then(() =>
            downloadFile(url, destination, {
              onProgress,
              resume: false,
              redirectsLeft,
              signal,
            }),
          )
          .then(resolve, reject);
      };
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
        downloadFile(nextUrl, destination, {
          onProgress,
          resume,
          redirectsLeft: redirectsLeft - 1,
          signal,
        }).then(resolve, reject);
        return;
      }

      if (existingBytes > 0 && status === 416) {
        response.resume();
        if (completedDownloadTotal(response.headers['content-range']) === existingBytes) {
          onProgress?.(existingBytes, existingBytes);
          resolve();
          return;
        }
        restartDownload();
        return;
      }

      const resumedTotalBytes =
        status === 206
          ? resumedDownloadTotal(response.headers['content-range'], existingBytes)
          : undefined;
      const append = existingBytes > 0 && status === 206 && resumedTotalBytes !== undefined;
      if (existingBytes > 0 && status === 206 && !append) {
        restartDownload();
        return;
      }
      if (status !== 200 && !append) {
        response.resume();
        reject(new Error(`Failed to download ${url}: HTTP ${status}`));
        return;
      }

      const output = fs.createWriteStream(destination, { flags: append ? 'a' : 'w' });
      const totalBytes = append
        ? resumedTotalBytes
        : contentLength(response.headers['content-length']);
      let downloadedBytes = append ? existingBytes : 0;
      onProgress?.(downloadedBytes, totalBytes);
      response.on('data', (chunk: Buffer) => {
        downloadedBytes += chunk.length;
        onProgress?.(downloadedBytes, totalBytes);
      });
      pipeline(response, output).then(resolve, reject);
    });
    request.setTimeout(DOWNLOAD_IDLE_TIMEOUT_MS, () => {
      request.destroy(new Error(`Timed out downloading ${url}: no response received`));
    });
    const abort = () => request.destroy(createAbortError());
    signal?.addEventListener('abort', abort, { once: true });
    const cleanup = () => signal?.removeEventListener('abort', abort);
    request.once('close', cleanup);
    request.on('error', reject);
  });
}

function resumedDownloadTotal(
  value: string | undefined,
  expectedStart: number,
): number | undefined {
  const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(value ?? '');
  if (match === null) return undefined;
  const start = Number.parseInt(match[1], 10);
  const end = Number.parseInt(match[2], 10);
  const total = Number.parseInt(match[3], 10);
  return start === expectedStart && end >= start && total > end ? total : undefined;
}

function completedDownloadTotal(value: string | undefined): number | undefined {
  const match = /^bytes \*\/(\d+)$/.exec(value ?? '');
  if (match === null) return undefined;
  const total = Number.parseInt(match[1], 10);
  return total > 0 ? total : undefined;
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
  onWait: () => void,
  action: () => Promise<T>,
  signal?: AbortSignal,
): Promise<T | undefined> {
  await fsp.mkdir(path.dirname(lockDir), { recursive: true });
  let deadline = Date.now() + INSTALL_LOCK_TIMEOUT_MS;
  let loggedWait = false;
  const token = `${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  while (true) {
    throwIfAborted(signal);
    if (isComplete()) {
      return undefined;
    }
    try {
      await fsp.mkdir(lockDir);
      await writeInstallLockOwner(lockDir, token);
      break;
    } catch (e) {
      if (!isAlreadyExistsError(e)) throw e;
      if ((await isInstallLockOwnerAlive(lockDir)) === false) {
        log(`Removing abandoned language server install lock: ${lockDir}`);
        await quarantineStaleInstallLock(lockDir);
        continue;
      }
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
        onWait();
      }
      await delay(INSTALL_LOCK_RETRY_DELAY_MS, signal);
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

async function isInstallLockOwnerAlive(lockDir: string): Promise<boolean | undefined> {
  const ownerPath = await findInstallLockOwnerPath(lockDir);
  if (ownerPath === undefined) return undefined;
  try {
    const owner = JSON.parse(await fsp.readFile(ownerPath, 'utf8')) as { pid?: unknown };
    if (!Number.isSafeInteger(owner.pid) || (owner.pid as number) <= 0) return undefined;
    process.kill(owner.pid as number, 0);
    return true;
  } catch (e) {
    if (isNoSuchProcessError(e)) return false;
    return undefined;
  }
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

function isNoSuchProcessError(e: unknown): boolean {
  return typeof e === 'object' && e !== null && 'code' in e && e.code === 'ESRCH';
}

function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
      reject(createAbortError());
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort);
      resolve();
    }, ms);
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
  });
}

async function sha256(file: string): Promise<string> {
  const hash = createHash('sha256');
  const input = fs.createReadStream(file);
  for await (const chunk of input) {
    hash.update(chunk);
  }
  return hash.digest('hex');
}

async function extractServerBundle(
  archivePath: string,
  serverDir: string,
  signal?: AbortSignal,
): Promise<void> {
  throwIfAborted(signal);
  const lower = archivePath.toLowerCase();
  const tmpDir = `${serverDir}.tmp`;

  await fsp.rm(serverDir, { recursive: true, force: true });
  await fsp.rm(tmpDir, { recursive: true, force: true });

  // Keep archive layout handling in sync with community/kotlin-vscode/unpack-server.mjs.
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) {
    await fsp.mkdir(serverDir, { recursive: true });
    await run(tarCommand(), ['-xzf', archivePath, '--strip-components=1', '-C', serverDir], signal);
  } else if (lower.endsWith('.zip')) {
    await fsp.mkdir(serverDir, { recursive: true });
    await run(tarCommand(), ['-xf', archivePath, '-C', serverDir], signal);
  } else if (lower.endsWith('.sit')) {
    await fsp.mkdir(tmpDir, { recursive: true });
    await run(tarCommand(), ['-xf', archivePath, '-C', tmpDir], signal);
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

function run(command: string, args: string[], signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    const abort = () => child.kill();
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
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
      code === 0 && !signal?.aborted
        ? resolve()
        : signal?.aborted
          ? reject(createAbortError())
          : reject(
              new Error(
                `${command} ${args.join(' ')} exited with code ${code}. stdout: ${stdout} stderr: ${stderr}`,
              ),
            ),
    );
    child.once('close', () => signal?.removeEventListener('abort', abort));
  });
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw createAbortError();
}

function createAbortError(): Error {
  const error = new Error('Language server download cancelled');
  error.name = 'AbortError';
  return error;
}

function appendProcessOutput(previousOutput: string, chunk: string): string {
  return (previousOutput + chunk).slice(-20_000);
}
