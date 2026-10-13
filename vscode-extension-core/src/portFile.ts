import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';

const DIRECTORY_NAME = 'intellij-lsp-servers';
const HASH_LENGTH = 16;

/**
 * The discovery file that maps a workspace to the TCP port of the server that serves it.
 *
 * The client finds a running server without asking anyone: it hashes the workspace root and reads the port
 * from a fixed per-user location, and it passes that same path to the server it starts (`--port-file`), so
 * only this side knows the recipe. The file lives in a directory named by the extension version: a server
 * started by another version of the extension publishes elsewhere, so an update never connects to a daemon
 * the previous version left running.
 */
export function computePortFilePath({
  workspaceRoot,
  version,
}: {
  workspaceRoot: string;
  version: string;
}): string {
  const hash = createHash('sha256').update(workspaceRoot).digest('hex').slice(0, HASH_LENGTH);
  return path.join(os.tmpdir(), DIRECTORY_NAME, version, `${hash}.port`);
}

/** Reads the published port, or `undefined` when the file is absent, empty, or not a valid port. */
export function readPortFile(portFilePath: string): number | undefined {
  let contents: string;
  try {
    contents = readFileSync(portFilePath, 'utf8');
  } catch {
    return undefined;
  }
  const port = Number.parseInt(contents.trim(), 10);
  if (!Number.isInteger(port) || port <= 0 || port > 65_535) return undefined;
  return port;
}
