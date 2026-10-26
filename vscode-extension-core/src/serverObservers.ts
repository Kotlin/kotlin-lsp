/** One spawn of the router by this window: the command, the arguments and the environment. */
export interface ServerLaunch {
  executable: string;
  args: string[];
  env: Readonly<Record<string, string | undefined>>;
}

/**
 * The observers of the server of a window. An extension that publishes the server for another
 * client gives them in its activation options. The core calls each one apart from the client: an
 * exception or a rejected promise of an observer goes to the log.
 */
export interface ServerObservers {
  /** After each spawn of the router with a server launcher and no dev port. */
  onServerLaunched?: (launch: ServerLaunch) => void | Promise<void>;
  /** After each successful reload of the workspace, with the options and the client of it. */
  onWorkspaceReloaded?: (options: Record<string, unknown>, client: object) => void | Promise<void>;
}

/** Calls an observer, and keeps its exception or its rejected promise away from the caller. */
export function notifyObserver({
  name,
  call,
  log,
}: {
  name: string;
  call: () => void | Promise<void>;
  log: (text: string) => void;
}): void {
  const report = (error: unknown) =>
    log(
      `The observer ${name} failed: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`,
    );
  try {
    const result = call();
    if (result instanceof Promise) result.catch(report);
  } catch (error) {
    report(error);
  }
}

export interface LaunchFacts {
  /** Whether the window starts the router, and not the server with `--stdio`. */
  throughRouter: boolean;
  /** The server launcher, which a dev port can leave out. */
  launcherPath: string | undefined;
  /** The dev port, or -1. */
  devPort: number;
  command: string;
  args: string[];
  env: Readonly<Record<string, string | undefined>>;
}

/**
 * The launch that the observer gets, or undefined for a spawn that does not count. A spawn counts
 * when the window starts the router with a server launcher and with no dev port.
 */
export function launchToPublish({
  throughRouter,
  launcherPath,
  devPort,
  command,
  args,
  env,
}: LaunchFacts): ServerLaunch | undefined {
  if (!throughRouter || launcherPath === undefined || devPort !== -1) return undefined;
  return { executable: command, args: [...args], env: { ...env } };
}

/**
 * Tells the observer of the launches of a spawn that counts, apart from the client. startServer
 * calls it after `waitForSpawn`, so a spawn that does not count reaches no observer.
 */
export function notifyServerLaunched({
  observers,
  facts,
  log,
}: {
  observers: ServerObservers;
  facts: LaunchFacts;
  log: (text: string) => void;
}): void {
  const onServerLaunched = observers.onServerLaunched;
  const launch = launchToPublish(facts);
  if (onServerLaunched === undefined || launch === undefined) return;
  notifyObserver({ name: 'onServerLaunched', call: () => onServerLaunched(launch), log });
}

/**
 * Sends a reload, applies its options, and only then notifies. A reload that fails or is cancelled
 * throws before the notice, so no observer hears of it.
 */
export async function reloadAndNotify({
  send,
  apply,
  notify,
}: {
  send: () => Promise<unknown>;
  apply: () => void;
  notify: () => void;
}): Promise<void> {
  await send();
  apply();
  notify();
}
