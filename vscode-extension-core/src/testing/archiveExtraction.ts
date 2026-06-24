import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { constants as fsConstants } from 'node:fs';
import { access, mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';

const EXTRACTION_READY_MARKER_FILE_NAME = '.ready';
const MAX_LOG_OUTPUT_CHARS = 20_000;

export async function extractArchiveToStaticDirectory(
  archivePath: string,
  targetPath: string,
): Promise<string> {
  const parentPath = dirname(targetPath);
  const folderName = basename(targetPath);
  const cacheKey = await archiveKey(archivePath);
  return extractArchive(archivePath, cacheKey, parentPath, folderName);
}

export async function extractArchiveToCacheDirectory(
  archivePath: string,
  targetPath: string,
): Promise<string> {
  const cacheKey = await archiveKey(archivePath);
  return extractArchive(archivePath, cacheKey, targetPath, cacheKey);
}

export async function archiveKey(archivePath: string): Promise<string> {
  const archiveStats = await stat(archivePath);
  return createHash('sha256')
    .update(`${archivePath}:${archiveStats.mtimeMs}:${archiveStats.size}`)
    .digest('hex')
    .slice(0, 24);
}

export async function findArchivePath(artifactDirectories: string[]): Promise<string | undefined> {
  const archiveExtension = getPreferredArchiveExtension();

  for (const artifactDirectory of artifactDirectories) {
    if (!(await pathExists(artifactDirectory))) {
      continue;
    }

    const entries = await readdir(artifactDirectory);
    const archives = entries
      .filter((entryName) => entryName.endsWith(archiveExtension))
      .sort()
      .reverse();
    const prioritizedArchives = prioritizeArchivesByArchitecture(archives);
    if (prioritizedArchives.length === 0) {
      continue;
    }

    return join(artifactDirectory, prioritizedArchives[0]);
  }

  return undefined;
}

function prioritizeArchivesByArchitecture(archiveNames: string[]): string[] {
  const withArm64Tag = archiveNames.filter((archiveName) => archiveName.includes('aarch64'));
  const withoutArm64Tag = archiveNames.filter((archiveName) => !archiveName.includes('aarch64'));
  if (process.arch === 'arm64') {
    return [...withArm64Tag, ...withoutArm64Tag];
  }
  return [...withoutArm64Tag, ...withArm64Tag];
}

function getPreferredArchiveExtension(): '.sit' | '.tar.gz' | '.win.zip' {
  if (process.platform === 'win32') {
    return '.win.zip';
  }

  if (process.platform === 'darwin') {
    return '.sit';
  }

  return '.tar.gz';
}

async function extractArchive(
  archivePath: string,
  archiveKey: string,
  targetParentPath: string,
  targetDirectoryName: string,
): Promise<string> {
  const extractionDirectory = join(targetParentPath, targetDirectoryName);
  const readyMarkerPath = join(extractionDirectory, EXTRACTION_READY_MARKER_FILE_NAME);
  await mkdir(targetParentPath, { recursive: true });
  await pruneExtractionCacheDirectory(targetParentPath, targetDirectoryName);
  if (await isActual(readyMarkerPath, archiveKey)) {
    return extractionDirectory;
  }

  const stagingDirectory = `${extractionDirectory}.tmp-${process.pid}-${Date.now()}`;
  await rm(stagingDirectory, { recursive: true, force: true });
  await mkdir(stagingDirectory, { recursive: true });
  try {
    // `tar` is GNU tar on Linux and bsdtar (libarchive) on macOS/Windows; both
    // auto-detect the format with `-xf` (.tar.gz, .sit, and .win.zip are all read)
    // and support `--strip-components` to drop the archive's top-level folder.
    await runCommand('tar', ['-xf', archivePath, '--strip-components=1', '-C', stagingDirectory]);
    await writeFile(
      join(stagingDirectory, EXTRACTION_READY_MARKER_FILE_NAME),
      `${archiveKey}\n`,
      'utf8',
    );

    await rm(extractionDirectory, { recursive: true, force: true });
    await rename(stagingDirectory, extractionDirectory);
    return extractionDirectory;
  } catch (error) {
    await rm(stagingDirectory, { recursive: true, force: true });
    throw error;
  }
}

async function pruneExtractionCacheDirectory(
  targetPath: string,
  activeCacheKey: string,
): Promise<void> {
  const entries = await readdir(targetPath, { withFileTypes: true });
  await Promise.all(
    entries.map(async (entry) => {
      const entryName = entry.name.toString();
      if (!entry.isDirectory() || !isCacheKeyEntryName(entryName) || entryName === activeCacheKey) {
        return;
      }
      await rm(join(targetPath, entryName), {
        recursive: true,
        force: true,
      });
    }),
  );
}

function isCacheKeyEntryName(entryName: string): boolean {
  return /^[a-f0-9]{24}$/.test(entryName);
}

async function runCommand(
  command: string,
  args: string[],
  options: {
    cwd?: string;
  } = {},
): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'], cwd: options.cwd });
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
    child.on('exit', (code, signal) => {
      if (code === 0) {
        resolve();
      } else {
        reject(
          new Error(
            `Command ${command} ${args.join(' ')} failed (code=${code}, signal=${signal}). stdout: ${stdout} stderr: ${stderr}`,
          ),
        );
      }
    });
  });
}

function appendProcessOutput(previousOutput: string, chunk: string): string {
  return (previousOutput + chunk).slice(-MAX_LOG_OUTPUT_CHARS);
}

async function isActual(markerPath: string, cacheKey: string): Promise<boolean> {
  try {
    const content = await readFile(markerPath, 'utf8');
    return content.trim() === cacheKey;
  } catch {
    return false;
  }
}

async function pathExists(targetPath: string): Promise<boolean> {
  try {
    await access(targetPath, fsConstants.F_OK);
    return true;
  } catch {
    return false;
  }
}
