import * as path from 'node:path';

/**
 * The `lsp-router` binary, which the extension speaks LSP to over stdio.
 *
 * The router decides which language server serves the workspace: it reads `initialize`, finds the
 * server that already serves that workspace, and starts one when none does. Several editors on one
 * workspace therefore share one server, and the extension needs to know nothing about ports.
 *
 * The binary ships with the language server, in the same `bin` directory as its launcher, so
 * every client of the server has it. It is built from `language-server/native/lsp-router`.
 */

/** Overrides the router that ships with the server, for work on the router itself. */
const ROUTER_PATH_VARIABLE = 'INTELLIJ_LSP_ROUTER';

export function routerFileName(platform: NodeJS.Platform = process.platform): string {
  return platform === 'win32' ? 'lsp-router.exe' : 'lsp-router';
}

/** The router that ships beside [launcherPath], the launcher of the same server. */
export function routerPathForLauncher(
  launcherPath: string,
  platform: NodeJS.Platform = process.platform,
): string {
  return path.join(path.dirname(launcherPath), routerFileName(platform));
}

/**
 * The router to run: the one named by [ROUTER_PATH_VARIABLE], or the one beside [launcherPath].
 *
 * The variable is for work on the router itself, and it is the only way to name a router when no
 * launcher is resolved. That happens with a dev server port and no configured server path: the
 * extension then has no server directory to take the router from.
 *
 * The variable is read from the environment rather than from a setting, because it belongs to a
 * developer's machine and not to a workspace.
 */
export function routerPath({
  launcherPath,
  env = process.env,
  platform = process.platform,
}: {
  launcherPath?: string;
  env?: NodeJS.ProcessEnv;
  platform?: NodeJS.Platform;
}): string | undefined {
  const override = env[ROUTER_PATH_VARIABLE]?.trim();
  if (override !== undefined && override.length > 0) return override;
  if (launcherPath === undefined) return undefined;
  return routerPathForLauncher(launcherPath, platform);
}

/**
 * The arguments for a router that may have to start a server.
 *
 * Everything in [serverArgs] goes to the server unchanged. The router adds only what makes the
 * server a shared daemon: the socket, the multi-client mode, the workspace root, and the idle timeout.
 */
export function routerArgs({
  launcherPath,
  serverArgs,
}: {
  launcherPath: string;
  serverArgs: readonly string[];
}): string[] {
  return ['--server-launcher', launcherPath, '--', ...serverArgs];
}
