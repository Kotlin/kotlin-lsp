import * as vscode from 'vscode';
import { workspace } from 'vscode';
import {
  CloseAction,
  Disposable,
  ErrorHandler,
  LanguageClient,
  LanguageClientOptions,
  NotificationType,
  RequestType,
  ResponseError,
  ServerOptions,
  State,
  StateChangeEvent,
  StreamInfo,
} from 'vscode-languageclient/node';
import { chmodSync } from 'fs';
import { rm } from 'node:fs/promises';
import * as net from 'node:net';
import * as os from 'node:os';
import { type ChildProcessWithoutNullStreams, spawn } from 'node:child_process';
import {
  type AcceptedEulaHashProvider,
  getBuildOutputChannel,
  getContext,
  getOutputChannel,
  logInfo,
  reloadWorkspace,
  revealBuildLog,
} from './extension';
import { runWithEulaGate } from './eulaGate';
import {
  isSettingsDocument,
  sanitizeBoolean,
  sanitizeBuildTools,
  sanitizeConfiguredProjects,
  sanitizeOptionalString,
  type BuiltinInitializationOptions,
  type SettingProblem,
  type SettingsChangeAction,
  settingsChangeAction,
} from './initializationSettings';
import {
  clearBuildError,
  setBuildError,
  setBuildToolConflict,
  setLspActionsAvailable,
  updateLspStatusBar,
} from './statusBar';
import { middleware } from './middleware';
import * as readline from 'node:readline';
import {
  discardServerBundleDownload,
  ensureServerLauncher,
  removeDownloadedServerBundle,
  ServerBundleChecksumError,
  type ServerBundlePhase,
  serverBundleStoragePath,
  serverLauncherPath,
} from './serverBundleDownload';
import { type ClientFeatureFactory, startClientWithFeatures } from './clientFeatureFactories';
import { isDataSharingChoice, isRegion } from './consentValues';
import {
  type LaunchedServerState,
  LaunchedServerStartup,
  shouldSuppressRestart,
} from './launchedServerStartup';
import {
  registerChooseActionMenuHandler,
  registerCopyToClipboardHandler,
  registerIntellijExtensionsInitOption,
} from './intellijExtensions';
import {
  handleCancelledServerDownload,
  handleServerDownloadChecksumMismatch,
} from './serverDownloadRecovery';
import { proxyJvmOptions } from './proxySettings';
import {
  buildToolConflictState,
  createLatestSnapshotTracker,
  type WorkspaceImportStatus,
} from './statusBarModel';

interface ExtensionPackageJson {
  name?: string;
  displayName?: string;
  contributes?: {
    languages?: Array<{ id: string }>;
  };
}

const LAUNCHED_SERVER_START_TIMEOUT_MS = 60_000;
const LAUNCHED_SERVER_EXIT_WAIT_MS = 1_000;
const LAUNCHED_SERVER_STOP_TIMEOUT_MS = 6_000;
const LOCAL_SERVER_CONNECTION_TIMEOUT_MS = 10_000;
const CONNECTION_RETRY_DELAY_MS = 100;

const LANGUAGE_CLIENT_ID = 'intellij';
const OPT_DEV_SERVER_PORT = 'intellij.dev.serverPort';
const OPT_DEV_SERVER_TIMEOUT = 'intellij.dev.serverTimeoutMs';
const OPT_SERVER_PATH = 'intellij.serverPath';
const OPT_JVM_ARGS = 'intellij.additionalJvmArgs';
const OPT_DEFAULT_WORKSPACE_SDK = 'intellij.jdkForSymbolResolution';
const OPT_BUILD_TOOL = 'intellij.buildTool';
const OPT_DATA_SHARING = 'intellij.dataSharing';
const OPT_REGION = 'intellij.region';
const OPT_PROJECTS = 'intellij.projects';
const OPT_DISABLE_ROCKS_DB_WAL = 'intellij.disableRocksDBWriteAheadLog';
const OPT_HTTP_PROXY = 'http.proxy';
const OPT_HTTP_PROXY_SUPPORT = 'http.proxySupport';

const INDEX_DIR_STATE_KEY = 'jetbrains.intellij.indexDir';

let _client: LanguageClient | undefined;
let startLspClientPromise: Promise<void> | undefined;
let lspClientStartRequests = 0;
let restartRequestedDuringStart = false;
let bundledServerLauncherCache: { key: string; promise: Promise<string> } | undefined;
let bundledServerSetupPhase: ServerBundlePhase = 'downloading';
let configuredClientFeatureFactories: ClientFeatureFactory[] = [];
/** The launched server outliving a single start, so a later stop can still terminate it. */
let launchedServer: LaunchedServerStartup | undefined;
/**
 * Settings changed since the server last read them. Cleared when it reads them again, including by
 * a reload the user did not ask for, such as the one a saved build file triggers.
 */
let pendingInitializationOptionsChange = false;
let pendingLaunchChange = false;
/** What the server last received, to tell a real change from one that sanitizes away to the same. */
let sentInitializationOptions: string | undefined;

interface ImportLogParams {
  type: 1 | 2 | 3;
  message: string;
  /** Build-tool display name, e.g. "Maven" / "Gradle" / "Bazel". Set on started and failed events. */
  tool?: string;
  failed?: boolean;
  succeeded?: boolean;
  started?: boolean;
}

const importLogNotification = new NotificationType<ImportLogParams>('intellij/importLog');

const workspaceImportStatusNotification = new NotificationType<WorkspaceImportStatus>(
  'intellij/workspaceImportStatus',
);
const workspaceImportStatusRequest = new RequestType<
  Record<string, never>,
  WorkspaceImportStatus,
  void
>('intellij/workspaceImportStatus');
const CHOOSE_BUILD_TOOL_ACTION = 'Choose Build Tool…';

const clientSubscriptions: ((client: LanguageClient, stateChange: StateChangeEvent) => void)[] = [];

export type InitializationOptionsContributor = () => Record<string, unknown>;
export type { ClientFeatureFactory } from './clientFeatureFactories';

export type { ConfiguredProject } from './initializationSettings';

const initializationOptionsContributors: InitializationOptionsContributor[] = [];
/** Settings the contributors read, so a change to one is noticed like a change to a builtin. */
const contributedSettings: string[] = [];

export function registerInitializationOptionsContributor(
  contributor: InitializationOptionsContributor,
  { settings = [] }: { settings?: readonly string[] } = {},
): void {
  initializationOptionsContributors.push(contributor);
  contributedSettings.push(...settings);
}

interface LspClientPolicyOptions {
  getAcceptedEulaHash: AcceptedEulaHashProvider;
  checkEulaAccepted: () => Promise<boolean>;
  clientFeatureFactories?: ClientFeatureFactory[];
}

export function initLspClient({
  getAcceptedEulaHash,
  checkEulaAccepted,
  clientFeatureFactories = [],
}: LspClientPolicyOptions): void {
  setLspActionsAvailable(true);
  configuredClientFeatureFactories = [...clientFeatureFactories];
  registerIntellijExtensionsInitOption();
  const restartServer = (): Promise<boolean> =>
    runWithEulaGate({
      checkEulaAccepted,
      action: () => startLspClient({ getAcceptedEulaHash, restartIfStarting: true }),
    });
  getContext().subscriptions.push(
    Disposable.create(async () => await stopLspClient()),
    vscode.commands.registerCommand('jetbrains.kotlin.restartLsp', async () => {
      if (!(await restartServer())) return;
      await vscode.window.showInformationMessage(extensionDisplayName() + ' restarted');
    }),
    vscode.commands.registerCommand('jetbrains.kotlin.clearCachesAndRestartLsp', async () => {
      await clearCachesAndRestart({ getAcceptedEulaHash, checkEulaAccepted });
    }),
  );
  registerSettingsChangeWatcher(restartServer);
  // Remember the index location the server reports on each successful start, so we can still
  // clear caches when the server later fails to start (and thus reports no `indexDir`).
  subscribeToClientEvent((client, stateChange) => {
    if (stateChange.newState !== State.Running) return;
    const indexDir = indexDirFromClient(client);
    if (indexDir) {
      void getContext().workspaceState.update(INDEX_DIR_STATE_KEY, indexDir);
    }
  });
}

/** The index directory the server reported in its `initialize` result, if it is running. */
function indexDirFromClient(client: LanguageClient | undefined): string | undefined {
  const experimental = client?.initializeResult?.capabilities?.experimental as
    | { indexDir?: string }
    | undefined;
  return experimental?.indexDir;
}

const INDEX_DELETE_MAX_ATTEMPTS = 5;
const INDEX_DELETE_RETRY_DELAY_MS = 200;

/**
 * Stops the language server, deletes its on-disk index/cache directory, then starts it again,
 * forcing a clean reindex. The index location is reported by the server in the `initialize`
 * result (`capabilities.experimental.indexDir`); we read it from the running client, falling back
 * to the value persisted on the last successful start when the server isn't running. Deletion
 * happens while the server is down so the RocksDB lock on the directory is released first.
 */
async function clearCachesAndRestart({
  getAcceptedEulaHash,
  checkEulaAccepted,
}: LspClientPolicyOptions): Promise<void> {
  // Prefer the running server's reported location; fall back to the last one we persisted so the
  // action still works when the server fails to start (e.g. because of the very caches to clear).
  const indexDir =
    indexDirFromClient(getLspClient()) ??
    getContext().workspaceState.get<string>(INDEX_DIR_STATE_KEY);

  const externalPort = configOption<number>(OPT_DEV_SERVER_PORT) ?? -1;
  if (externalPort !== -1) {
    // The server runs externally (a fixed dev port), so we don't control its lifecycle and
    // can't release the index lock — clearing its caches from here would corrupt the live
    // index. Ask the user to stop it and delete the directory manually.
    const detail = indexDir
      ? `${extensionDisplayName()} is connected to an external language server on port ${externalPort}, so its caches can't be cleared from here.\n\nStop that server and delete its index directory manually:\n${indexDir}`
      : `${extensionDisplayName()} is connected to an external language server on port ${externalPort}, so its caches can't be cleared from here.\n\nStop that server and delete its index directory manually.`;
    const choice = await vscode.window.showWarningMessage(
      'Cannot clear caches for an external language server',
      { modal: true, detail },
      ...(indexDir ? ['Copy Path'] : []),
    );
    if (choice === 'Copy Path' && indexDir) {
      await vscode.env.clipboard.writeText(indexDir);
    }
    return;
  }

  const confirmation = await vscode.window.showWarningMessage(
    `Clear caches and restart ${extensionDisplayName()}?`,
    {
      modal: true,
      detail: indexDir
        ? `The index directory will be deleted and rebuilt:\n${indexDir}`
        : 'The language server will be restarted. The index location is unknown (the server is not running), so no caches will be cleared.',
    },
    'Clear and Restart',
  );
  if (confirmation !== 'Clear and Restart') return;

  const eulaAccepted = await checkEulaAccepted();
  if (!eulaAccepted) return;

  await stopLspClient();

  const cleared = indexDir ? await deleteIndexDir(indexDir) : false;

  await startLspClient({ getAcceptedEulaHash, restartIfStarting: true });

  await vscode.window.showInformationMessage(
    cleared
      ? `${extensionDisplayName()} restarted (caches cleared)`
      : `${extensionDisplayName()} restarted`,
  );
}

/**
 * Deletes the index directory, retrying a few times to tolerate the OS releasing the server's
 * file handles after it exits (notably on Windows). Returns `true` if the directory was removed,
 * `false` if every attempt failed (the failure is reported but does not abort the restart).
 */
async function deleteIndexDir(indexDir: string): Promise<boolean> {
  for (let attempt = 1; attempt <= INDEX_DELETE_MAX_ATTEMPTS; attempt++) {
    try {
      await rm(indexDir, { recursive: true, force: true });
      logInfo(`Cleared index directory: ${indexDir}`);
      return true;
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      if (attempt === INDEX_DELETE_MAX_ATTEMPTS) {
        logInfo(`Failed to clear index directory ${indexDir}: ${message}`);
        await vscode.window.showErrorMessage(`Failed to clear caches in ${indexDir}: ${message}`);
        return false;
      }
      await new Promise((resolve) => setTimeout(resolve, INDEX_DELETE_RETRY_DELAY_MS));
    }
  }
  return false;
}

/**
 * Subscribes to the LSP client events. The subscription will be called on every state change.
 *
 * We cannot subscribe to the client events directly because the client instance may be changed
 *
 * @param subscription - function to call on state change
 * @returns a disposable that removes the subscription from the persistent `clientSubscriptions`
 *   list, so a caller with a shorter lifetime than the module (or the extension across
 *   activate/deactivate cycles) does not leak listeners.
 */
export function subscribeToClientEvent(
  subscription: (client: LanguageClient, stateChange: StateChangeEvent) => void,
): vscode.Disposable {
  clientSubscriptions.push(subscription);
  return {
    dispose: () => {
      const i = clientSubscriptions.indexOf(subscription);
      if (i >= 0) clientSubscriptions.splice(i, 1);
    },
  };
}

/**
 * LSP client if it's running, undefined otherwise. The results should not be cached, because
 * it may be changed on restarts
 */
export function getLspClient(): LanguageClient | undefined {
  return _client;
}

export function isLspClientStartPending(): boolean {
  return lspClientStartRequests > 0 || startLspClientPromise !== undefined;
}

function beginLspClientStart(): Disposable {
  lspClientStartRequests++;
  updateLspStatusBar();
  let disposed = false;
  return Disposable.create(() => {
    if (disposed) return;
    disposed = true;
    lspClientStartRequests--;
    updateLspStatusBar();
  });
}

export async function withLspClientStartPending<T>(action: () => Promise<T>): Promise<T> {
  const pendingStart = beginLspClientStart();
  try {
    return await action();
  } finally {
    pendingStart.dispose();
  }
}

/**
 * Starts the LSP client applying all user options. If the client is already running, restarts it.
 */
export interface StartLspClientOptions {
  getAcceptedEulaHash: AcceptedEulaHashProvider;
  restartIfStarting?: boolean;
}

export function startLspClient({
  getAcceptedEulaHash,
  restartIfStarting = false,
}: StartLspClientOptions): Promise<void> {
  if (startLspClientPromise !== undefined) {
    if (restartIfStarting) restartRequestedDuringStart = true;
    return startLspClientPromise;
  }
  const promise = (async () => {
    do {
      restartRequestedDuringStart = false;
      await doStartLspClient(getAcceptedEulaHash);
    } while (restartRequestedDuringStart);
  })().finally(() => {
    if (startLspClientPromise === promise) {
      startLspClientPromise = undefined;
      updateLspStatusBar();
    }
  });
  startLspClientPromise = promise;
  updateLspStatusBar();
  return promise;
}

async function doStartLspClient(getAcceptedEulaHash: AcceptedEulaHashProvider): Promise<void> {
  const launchedServerState: LaunchedServerState = { initialStartSettled: false };
  const created = await createLspClient(getAcceptedEulaHash, launchedServerState);
  if (!created) return;
  const { client: runClient, initializationOptions } = created;
  await stopLspClient();
  _client = runClient;
  getContext().subscriptions.push(
    _client.onDidChangeState((e) => {
      // Running means the server answered initialize, so its startup no longer needs a watchdog.
      // This also covers the client's own restarts, which do not go through doStartLspClient.
      if (e.newState === State.Running) launchedServerState.currentAttempt?.settle();
      if (e.newState === State.Stopped) {
        setBuildToolConflict({ blocked: false, promptDismissed: false });
      }
      for (const subscription of clientSubscriptions.slice()) {
        try {
          subscription(runClient, e);
        } catch (error) {
          logInfo(
            `Language client state subscriber failed: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`,
          );
        }
      }
    }),
  );

  // The process is spawned as the client starts, so this is what it will be launched with.
  const launchSettings = launchSettingsSnapshot();
  const workspaceImportStatus = registerWorkspaceImportStatusHandler(runClient);
  try {
    await startClientWithFeatures(runClient, configuredClientFeatureFactories);
    // A new process read the launch settings too, which a reload cannot. Edits made while it was
    // starting are not in it, so they stay pending.
    pendingLaunchChange = launchSettingsSnapshot() !== launchSettings;
    markInitializationOptionsApplied(initializationOptions);
    registerImportLogHandler(runClient);
    registerCopyToClipboardHandler(runClient);
    registerChooseActionMenuHandler(runClient);
    void workspaceImportStatus.refresh().catch((error: unknown) => {
      logInfo(
        `Failed to read workspace import status: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`,
      );
    });
  } catch (e) {
    const launchedServerAttempt = launchedServerState.currentAttempt;
    await launchedServerAttempt?.waitForExit(LAUNCHED_SERVER_EXIT_WAIT_MS);
    try {
      await runClient.dispose();
    } catch {
      // dispose() marks the client as disposed before stop(), which can reject in StartFailed state.
      // The disposed flag prevents an already queued restart from starting another server.
    }
    launchedServerAttempt?.kill();
    // A second chance to observe the exit code: only the first exit is latched, so the kill above
    // cannot mask a natural one, and expiredBuild below needs it to classify the failure.
    await launchedServerAttempt?.waitForExit(LAUNCHED_SERVER_EXIT_WAIT_MS);
    if (_client === runClient) _client = undefined;
    if (launchedServer === launchedServerAttempt) launchedServer = undefined;
    updateLspStatusBar();

    if (launchedServerAttempt?.expiredBuild) {
      void vscode.window.showErrorMessage(
        `${extensionDisplayName()} could not start the language server because the bundled build has expired. Update the extension and try again.`,
        { modal: true },
      );
      return;
    }
    // The server answered, so its own reason is the accurate one: report it instead of the exit
    // that follows, and without the detail already written to the log.
    const rejection = launchedServerState.initializationRejection;
    if (rejection !== undefined) throw new Error(rejection);
    const cause = e instanceof Error ? e : new Error(String(e));
    throw launchedServerAttempt?.startupError(cause) ?? cause;
  } finally {
    launchedServerState.initialStartSettled = true;
    launchedServerState.currentAttempt?.settle();
  }
}

export async function stopLspClient(): Promise<void> {
  await stopLspClientAndReport();
}

async function stopLspClientAndReport(): Promise<boolean> {
  if (!_client) {
    return stopLaunchedServer();
  }
  const client = _client;
  _client = undefined;
  updateLspStatusBar();
  let serverStopped: boolean;
  try {
    if (client.needsStop()) {
      await client.stop();
    }
  } catch (error) {
    if (!isWriteAfterEndError(error)) throw error;
  } finally {
    // kill() is a no-op once the shutdown handshake above made the process exit on its own.
    serverStopped = await stopLaunchedServer();
  }
  return serverStopped;
}

async function stopLaunchedServer(): Promise<boolean> {
  const server = launchedServer;
  if (!server) return true;

  const stopped = await server.killAndWaitForExit(LAUNCHED_SERVER_STOP_TIMEOUT_MS);
  if (stopped && launchedServer === server) launchedServer = undefined;
  return stopped;
}

function isWriteAfterEndError(error: unknown): boolean {
  return error instanceof Error && /\bwrite after end\b/i.test(error.message);
}

function registerImportLogHandler(client: LanguageClient): void {
  clearBuildError();
  const subscription = client.onNotification(importLogNotification, (p) => {
    const channel = getBuildOutputChannel();
    if (p.started) {
      // Reveal the Build output while the import runs
      revealBuildLog();
      return;
    }
    channel.appendLine(p.message);
    if (p.failed) {
      // Terminal failure events reveal the Build output and leave a status item as an entry point.
      void vscode.commands.executeCommand('jetbrains.showBuildLog');
      setBuildError(p.tool ?? 'Build');
    } else if (p.succeeded) {
      clearBuildError();
    }
  });
  getContext().subscriptions.push(subscription);
}

/**
 * Registers before the client starts so the initial blocked notification cannot be missed. The refresh request
 * seeds persisted state only when no live notification has arrived, either before or during the request.
 */
function registerWorkspaceImportStatusHandler(client: LanguageClient): {
  refresh(): Promise<void>;
} {
  setBuildToolConflict({ blocked: false, promptDismissed: false });
  let promptDismissed = false;
  const snapshots = createLatestSnapshotTracker<WorkspaceImportStatus>((status) => {
    const conflict = buildToolConflictState(status);
    setBuildToolConflict(conflict);
    const newlyDismissed = conflict.promptDismissed && !promptDismissed;
    promptDismissed = conflict.promptDismissed;
    if (!newlyDismissed) return;
    void vscode.window
      .showWarningMessage(
        'Project import will not start until you select a build tool.',
        CHOOSE_BUILD_TOOL_ACTION,
      )
      .then((choice) => {
        if (choice === CHOOSE_BUILD_TOOL_ACTION) {
          void reloadWorkspace({ showConfirmation: false });
        }
      });
  });
  const subscription = client.onNotification(workspaceImportStatusNotification, (status) => {
    snapshots.publish(status);
  });
  getContext().subscriptions.push(subscription);
  return {
    async refresh(): Promise<void> {
      await snapshots.refresh(() => client.sendRequest(workspaceImportStatusRequest, {}));
    },
  };
}

export function packageJson(): ExtensionPackageJson | undefined {
  return vscode.extensions.getExtension(getContext().extension.id)?.packageJSON as
    | ExtensionPackageJson
    | undefined;
}

function extensionDisplayName(): string {
  return packageJson()?.displayName ?? 'IntelliJ Language Server (fallback)';
}

function configOption<T>(name: string, scope?: vscode.ConfigurationScope): T | undefined {
  return (
    workspace.getConfiguration(undefined, scope).get(name) ??
    workspace.getConfiguration(undefined, scope).get(
      // TODO drop fallback
      name.replace('intellij.', 'kotlinLSP.'),
    )
  );
}

async function ensureBundledServerLauncher(): Promise<string> {
  const context = getContext();
  const isDevelopment = context.extensionMode === vscode.ExtensionMode.Development;
  const serverRoot = serverBundleStoragePath(packageJson()?.name ?? 'intellij-server');
  const cacheKey = context.extensionPath;
  if (bundledServerLauncherCache?.key !== cacheKey) {
    bundledServerSetupPhase = 'downloading';
    bundledServerLauncherCache = {
      key: cacheKey,
      promise: Promise.resolve(
        vscode.window.withProgress(
          {
            location: vscode.ProgressLocation.Notification,
            title: `${extensionDisplayName()}: language server setup`,
            cancellable: true,
          },
          async (progress, token) => {
            const controller = new AbortController();
            const cancellation = token.onCancellationRequested(() => controller.abort());
            try {
              const launcherPath = await ensureServerLauncher({
                extensionPath: context.extensionPath,
                serverRoot,
                log: logInfo,
                progress: (update) => {
                  bundledServerSetupPhase = update.phase;
                  progress.report(update);
                },
                signal: controller.signal,
                allowCachedServerWithoutMetadata: isDevelopment,
              });
              if (isDevelopment) {
                void vscode.window.showInformationMessage(
                  `${extensionDisplayName()} is running in development mode using the language server ${launcherPath}`,
                );
              }
              return launcherPath;
            } finally {
              cancellation.dispose();
            }
          },
        ),
      ),
    };
  }
  const cache = bundledServerLauncherCache;
  let launcherPath: string;
  try {
    launcherPath = await cache.promise;
  } catch (error) {
    if (bundledServerLauncherCache === cache) bundledServerLauncherCache = undefined;
    if (isAbortError(error)) {
      const resumedLauncher = await handleCancelledServerDownload({
        phase: bundledServerSetupPhase,
        showInformationMessage: (message, ...actions) =>
          vscode.window.showInformationMessage(message, ...actions),
        resumeDownload: ensureBundledServerLauncher,
        deleteDownloadedFiles: async () => {
          try {
            await discardServerBundleDownload(context.extensionPath, serverRoot, logInfo);
          } catch (discardError) {
            logInfo(
              `Failed to discard cancelled language server download: ${
                discardError instanceof Error ? discardError.message : String(discardError)
              }`,
            );
          }
        },
      });
      if (resumedLauncher !== undefined) return resumedLauncher;
    } else if (error instanceof ServerBundleChecksumError) {
      const redownloadedLauncher = await handleServerDownloadChecksumMismatch({
        showErrorMessage: (message, ...actions) =>
          vscode.window.showErrorMessage(message, ...actions),
        redownloadServer: ensureBundledServerLauncher,
      });
      if (redownloadedLauncher !== undefined) return redownloadedLauncher;
    }
    throw error;
  }
  if (os.platform() !== 'win32') {
    chmodSync(launcherPath, 0o755);
  }
  return launcherPath;
}

export async function removeDownloadedServerLauncher(): Promise<void> {
  const stopped = await stopLspClientAndReport();
  if (!stopped) throw new Error('Timed out waiting for the language server process to stop');
  bundledServerLauncherCache = undefined;
  const context = getContext();
  const serverRoot = serverBundleStoragePath(packageJson()?.name ?? 'intellij-server');
  await removeDownloadedServerBundle(context.extensionPath, serverRoot, logInfo);
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

export type ServerLauncherPreparation = { kind: 'external' } | { kind: 'launcher'; path: string };

export function isExternalServerConfigured(): boolean {
  return (configOption<number>(OPT_DEV_SERVER_PORT) ?? -1) !== -1;
}

export async function prepareBundledServerLauncher(): Promise<ServerLauncherPreparation> {
  if (isExternalServerConfigured()) return { kind: 'external' };
  return {
    kind: 'launcher',
    path: configuredServerLauncherPath() ?? (await ensureBundledServerLauncher()),
  };
}

export function prefetchBundledServerLauncher(): void {
  void prepareBundledServerLauncher().catch((error) => {
    logInfo(
      `Failed to prepare language server in the background: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  });
}

function getServerOptions(
  launchedServerState: LaunchedServerState,
  getAcceptedEulaHash: AcceptedEulaHashProvider,
): ServerOptions {
  const predefinedPort = configOption<number>(OPT_DEV_SERVER_PORT) ?? -1;
  if (predefinedPort !== -1) {
    // Connecting to an already-running (dev) server: we didn't start it, so we can't pass `--eula`.
    // The server skips EULA enforcement when running from sources, so no hash is needed here.
    return () =>
      getStreamInfoForRunningServer(
        predefinedPort,
        configOption<number>(OPT_DEV_SERVER_TIMEOUT) ?? LOCAL_SERVER_CONNECTION_TIMEOUT_MS,
      );
  }
  return () => {
    const launchedServerAttempt = new LaunchedServerStartup();
    launchedServerState.currentAttempt = launchedServerAttempt;
    return getStreamInfoForLaunchedServer({
      launchedServerAttempt,
      launcherPath: configuredServerLauncherPath(),
      getAcceptedEulaHash,
    });
  };
}

function configuredServerLauncherPath(): string | undefined {
  const serverPath = configOption<string>(OPT_SERVER_PATH);
  return serverPath === undefined ? undefined : serverLauncherPath(serverPath);
}

async function getStreamInfoForLaunchedServer({
  launchedServerAttempt,
  launcherPath,
  getAcceptedEulaHash,
}: {
  launchedServerAttempt: LaunchedServerStartup;
  launcherPath?: string;
  getAcceptedEulaHash: AcceptedEulaHashProvider;
}): Promise<StreamInfo> {
  const serverProcess = await startServer({
    launchedServerAttempt,
    launcherPath,
    getAcceptedEulaHash,
  });
  return { reader: serverProcess.stdout, writer: serverProcess.stdin };
}

async function startServer({
  launchedServerAttempt,
  launcherPath: configuredLauncherPath,
  getAcceptedEulaHash,
}: {
  launchedServerAttempt: LaunchedServerStartup;
  launcherPath?: string;
  getAcceptedEulaHash: AcceptedEulaHashProvider;
}): Promise<ChildProcessWithoutNullStreams> {
  const launcherPath = configuredLauncherPath ?? (await ensureBundledServerLauncher());

  const context = getContext();
  const args: string[] = [];
  args.push('--stdio');
  if (context.storageUri) {
    args.push('--system-path', context.storageUri.fsPath);
  }
  // The launcher owns the accepted-EULA hash: we start the process, so we pass it on the command line.
  // Clients that only connect to an already-running server cannot, which is why this is not in `initialize`.
  const eulaHash = getAcceptedEulaHash(context);
  if (eulaHash !== undefined) {
    args.push('--eula', eulaHash);
  }
  const userJvmOptions = getUserJvmOptions();
  const configuredProxyOptions = proxyJvmOptions(
    configOption<string>(OPT_HTTP_PROXY),
    configOption<string>(OPT_HTTP_PROXY_SUPPORT),
  );
  const rawDataSharing = configOption(OPT_DATA_SHARING);
  const dataSharing = isDataSharingChoice(rawDataSharing) ? rawDataSharing : 'none';
  const rawRegion = configOption(OPT_REGION);
  const region = isRegion(rawRegion) ? rawRegion : undefined;
  const env = buildLaunchEnvironment(
    process.env,
    configuredProxyOptions,
    userJvmOptions,
    dataSharing,
    region,
  );

  logInfo('Starting language server');
  logInfo(`  command: ${launcherPath}`);
  logInfo(`  args   : ${JSON.stringify(args)}`);
  logInfo(`  VM opts: ${JSON.stringify(userJvmOptions)}`);
  if (configuredProxyOptions.length > 0) logInfo('  proxy  : configured from VS Code settings');
  logInfo('');

  const serverProcess = spawn(launcherPath, args, {
    env,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  launchedServerAttempt.setProcess(serverProcess, (error) =>
    logInfo(`Language server process error: ${error.message}`),
  );
  launchedServer = launchedServerAttempt;

  const rlErr = readline.createInterface({
    input: serverProcess.stderr,
    terminal: false,
  });
  rlErr.on('line', (line: string) => logInfo(`[stderr] ${line}`));
  serverProcess.once('close', () => rlErr.close());

  await launchedServerAttempt.waitForSpawn();

  // Every launch is guarded, including the client's own restarts: its crash budget only reacts to a
  // closed connection, so a server that spawns but never answers initialize is invisible to it.
  launchedServerAttempt.startTimeout(LAUNCHED_SERVER_START_TIMEOUT_MS);
  return serverProcess;
}

async function getStreamInfoForRunningServer(port: number, timeoutMs: number): Promise<StreamInfo> {
  let timeout = timeoutMs;
  const deadline = Date.now() + timeoutMs;

  let error: unknown = null;
  while (timeout > 0) {
    try {
      const socket = await connectToPort(port, timeout);
      return { reader: socket, writer: socket };
    } catch (e) {
      logInfo(`Failed to connect to LSP server on port ${port}: ${e}`);
      error ??= e;
      await new Promise((resolve) => setTimeout(resolve, CONNECTION_RETRY_DELAY_MS));
      timeout = deadline - Date.now();
      if (timeout > 0) {
        logInfo(`Retrying connection to LSP server`);
      }
    }
  }

  if (error) {
    throw error;
  }

  throw new Error(`Failed to connect to LSP server on port ${port}`);
}

function buildDocumentSelector(): LanguageClientOptions['documentSelector'] {
  const contributedLanguageIds: string[] = (packageJson()?.contributes?.languages ?? []).map(
    (l) => l.id,
  );

  if (contributedLanguageIds.includes('kotlin') && !contributedLanguageIds.includes('java')) {
    // we want to be able to detect changes in Java files
    // to correctly reflect them in Kotlin, see LSP-1053
    contributedLanguageIds.push('java');
  }

  logInfo(`Serving languages: ${contributedLanguageIds.join(', ')}`);

  const supportedSchemes = ['file', 'jar', 'jrt', 'untitled'];
  const selector: NonNullable<LanguageClientOptions['documentSelector']> = [
    { scheme: 'jar', language: 'plaintext' },
    { scheme: 'jrt', language: 'plaintext' },
  ];

  for (const lang of contributedLanguageIds) {
    for (const scheme of supportedSchemes) {
      selector.push({ scheme, language: lang });
    }
  }
  return selector;
}

const OPEN_SETTINGS_ACTION = 'Open Settings';
const SHOW_LOG_ACTION = 'Show Log';

/**
 * Settings the server reads only from `initializationOptions`; a workspace reload resends them.
 * Language modules add their own through `registerInitializationOptionsContributor`.
 */
const INITIALIZATION_OPTION_SETTINGS = [
  OPT_PROJECTS,
  OPT_BUILD_TOOL,
  OPT_DEFAULT_WORKSPACE_SDK,
  OPT_DISABLE_ROCKS_DB_WAL,
];
/** Settings that become process arguments or environment, so only a new process applies them. */
const LAUNCH_SETTINGS = [
  OPT_JVM_ARGS,
  OPT_SERVER_PATH,
  OPT_DEV_SERVER_PORT,
  OPT_DEV_SERVER_TIMEOUT,
  OPT_HTTP_PROXY,
  OPT_HTTP_PROXY_SUPPORT,
];

/** Launch settings never reach `initializationOptions`, so their values are compared on their own. */
function launchSettingsSnapshot(): string {
  return JSON.stringify(LAUNCH_SETTINGS.map((setting) => configOption(setting)));
}
const SETTINGS_CHANGE_DEBOUNCE_MS = 500;
/** An "Add Item" click writes an empty entry, so give the fields time to be filled in. */
const INVALID_SETTINGS_SETTLE_MS = 10_000;
const SETTINGS_CHANGE_ACTION_LABELS: Record<Exclude<SettingsChangeAction, 'none'>, string> = {
  start: 'Start Server',
  restart: 'Restart Server',
  reload: 'Reload Workspace',
};

/**
 * Applying a change reimports and reindexes, so it is offered rather than done silently.
 * Editing settings.json fires repeatedly, hence the debounce: one prompt per burst of edits.
 */
function registerSettingsChangeWatcher(restartServer: () => Promise<boolean>): void {
  let debounce: NodeJS.Timeout | undefined;
  /** A save is the edit finished, so it needs no settle window even if a change event follows. */
  let savedSinceLastPrompt = false;
  let lastPromptedFor: string | undefined;
  const schedule = (delayMs: number, settled: boolean): void => {
    if (debounce) clearTimeout(debounce);
    debounce = setTimeout(() => void prompt(settled), delayMs);
  };
  // An ignored notification never settles, so a pending prompt cannot gate the next one.
  const prompt = async (timerSettled: boolean): Promise<void> => {
    const settled = timerSettled || savedSinceLastPrompt;
    // A client that is still starting is neither running nor absent, and labelling the action from
    // that state gets it wrong. Wait for it to settle either way.
    const client = getLspClient();
    if (client !== undefined && client.state !== State.Running) {
      schedule(SETTINGS_CHANGE_DEBOUNCE_MS, settled);
      return;
    }
    const affectsLaunch = pendingLaunchChange;
    const action = settingsChangeAction({
      affectsLaunch,
      affectsInitializationOptions: pendingInitializationOptionsChange,
      serverRunning: client !== undefined,
    });
    // An entry the settings editor just added is invalid until its fields are filled in, so wait
    // for the edits to stop before calling it broken.
    const { options, problems } = initializationOptionsPayload();
    if (problems.length > 0 && !settled) {
      schedule(INVALID_SETTINGS_SETTLE_MS, true);
      return;
    }
    // The pending flags stay set until the server accepts the settings, so a dismissed prompt or a
    // failed reload still counts as waiting to be applied.
    savedSinceLastPrompt = false;
    // Applying a value the server cannot decode would only drop it, so say so instead.
    reportSettingsProblems({ problems });
    if (problems.length > 0) return;
    // Deleting a value the server never received, such as an entry that was dropped as invalid,
    // leaves it with exactly what it already has. Nothing to apply, nothing to ask about.
    if (action === 'none') return;
    const payload = JSON.stringify(options);
    if (action === 'reload' && payload === sentInitializationOptions) return;
    // Saving again with nothing new since the last prompt is not worth a second notification.
    // Launch settings are not part of the payload, so their values make up the rest of the key.
    const promptFor = `${launchSettingsSnapshot()} ${payload}`;
    if (promptFor === lastPromptedFor) return;
    lastPromptedFor = promptFor;
    const label = SETTINGS_CHANGE_ACTION_LABELS[action];
    const choice = await vscode.window.showWarningMessage(
      `${extensionDisplayName()}: settings changed. ${label} to apply them.`,
      label,
    );
    if (choice !== label) return;
    // A notification can sit unanswered for a long time, so what to do is decided on the click.
    if (!affectsLaunch && getLspClient()?.state === State.Running) await reloadWorkspace();
    else await restartServer();
    // Both flags clear only once the server has the settings. Either one still set means the apply
    // did not take, and the change is still waiting, so it may be asked about again.
    if (pendingLaunchChange || pendingInitializationOptionsChange) lastPromptedFor = undefined;
  };
  getContext().subscriptions.push(
    workspace.onDidChangeConfiguration((event) => {
      const launch = LAUNCH_SETTINGS.some((s) => event.affectsConfiguration(s));
      const options = [...INITIALIZATION_OPTION_SETTINGS, ...contributedSettings].some((s) =>
        event.affectsConfiguration(s),
      );
      if (!launch && !options) return;
      pendingLaunchChange ||= launch;
      pendingInitializationOptionsChange ||= options;
      schedule(SETTINGS_CHANGE_DEBOUNCE_MS, false);
    }),
    // The only signal left when the saved value equals the current one, which raises no
    // configuration event at all. Debounced like a change, because the configuration is applied
    // after the save, and reading it here would still see the old value.
    workspace.onDidSaveTextDocument((document) => {
      if (!isSettingsDocument(document.uri.path)) return;
      savedSinceLastPrompt = true;
      schedule(SETTINGS_CHANGE_DEBOUNCE_MS, false);
    }),
    Disposable.create(() => clearTimeout(debounce)),
  );
}

let reportedSettingsProblems: string | undefined;

/**
 * Logs every time, but only warns when the problems changed, so neither a restart nor a save with
 * the same values still broken says it twice. Fixing them clears the memory, so breaking them the
 * same way again warns again.
 */
function reportSettingsProblems({ problems }: { problems: SettingProblem[] }): void {
  const detail = problems.map((p) => p.message).join('; ');
  const changed = reportedSettingsProblems !== detail;
  reportedSettingsProblems = detail;
  if (problems.length === 0) return;
  logInfo(`Ignoring invalid settings: ${detail}`);
  if (!changed) return;
  // One bad setting opens on that setting; several open on this extension's settings.
  const only = new Set(problems.map((p) => p.setting));
  const query =
    only.size === 1 ? `@id:${problems[0]?.setting}` : `@ext:${getContext().extension.id}`;
  void vscode.window
    .showWarningMessage(
      `${extensionDisplayName()} ignored invalid settings, so they will not apply: ${detail}.`,
      OPEN_SETTINGS_ACTION,
    )
    .then((choice) => {
      if (choice === OPEN_SETTINGS_ACTION) {
        void vscode.commands.executeCommand('workbench.action.openSettings', query);
      }
    });
}

/**
 * Keeps the server's diagnostics in the log, since it attaches the bundled EULA to some
 * `initialize` rejections, and returns the one-line summary to report instead.
 */
function logInitializationRejection(error: ResponseError<unknown>): string {
  logInfo(`The language server rejected the initialize request:\n${error.message}`);
  return error.message.split('\n')[0]?.trim() || 'the initialize request was rejected';
}

/**
 * Reports a failed `activateExtension` for hosts that have no policy lifecycle to do it for them.
 * The message carries the summary only, the detail is already in the log.
 */
export async function reportActivationFailure(error: unknown): Promise<void> {
  const detail = (error instanceof Error ? error.message : String(error)).trim();
  const summary = detail === '' ? 'could not start the language server' : detail;
  const choice = await vscode.window.showErrorMessage(
    `${extensionDisplayName()}: ${summary}`,
    SHOW_LOG_ACTION,
  );
  if (choice === SHOW_LOG_ACTION) getOutputChannel().show(true);
}

/** The settings-derived options, with every value the server could not decode dropped. */
function sanitizedInitializationSettings(): {
  values: BuiltinInitializationOptions;
  problems: SettingProblem[];
} {
  const folders = workspace.workspaceFolders ?? [];
  const defaultSdk = sanitizeOptionalString(
    OPT_DEFAULT_WORKSPACE_SDK,
    configOption(OPT_DEFAULT_WORKSPACE_SDK),
  );
  const buildTools = sanitizeBuildTools(
    OPT_BUILD_TOOL,
    folders.map((folder) => [folder.uri.toString(), configOption(OPT_BUILD_TOOL, folder.uri)]),
  );
  const projects = sanitizeConfiguredProjects(OPT_PROJECTS, configOption(OPT_PROJECTS));
  const disableWal = sanitizeBoolean(
    OPT_DISABLE_ROCKS_DB_WAL,
    configOption(OPT_DISABLE_ROCKS_DB_WAL),
  );
  return {
    values: {
      defaultSdk: defaultSdk.value,
      buildTools: buildTools.value,
      projects: projects.value,
      disableRocksDBWriteAheadLog: disableWal.value,
    },
    problems: [defaultSdk, buildTools, projects, disableWal].flatMap((setting) => setting.problems),
  };
}

function initializationOptionsPayload(): {
  options: Record<string, unknown>;
  problems: SettingProblem[];
} {
  const { values: builtinInitializationOptions, problems } = sanitizedInitializationSettings();
  const contributedInitializationOptions: Record<string, unknown> = Object.assign(
    {},
    ...initializationOptionsContributors.map((c) => c()),
  );
  return {
    options: { ...contributedInitializationOptions, ...builtinInitializationOptions },
    problems,
  };
}

/**
 * Assembles the server `initializationOptions` from the current VS Code settings. Read fresh each
 * time, so it reflects edits to e.g. `intellij.projects`. Used both for the initial `initialize` and
 * for the `intellij/reloadWorkspace` request, so a reload picks up settings changes without
 * reopening the folder.
 */
export function buildInitializationOptions(): Record<string, unknown> {
  const { options, problems } = initializationOptionsPayload();
  reportSettingsProblems({ problems });
  return options;
}

/**
 * Records what the server accepted. Called once the request carrying them succeeded, since a
 * reload that failed or was cancelled leaves the server with the options it already had, and the
 * settings are still waiting to reach it. Settings edited while the request was in flight are not
 * in what it accepted, so they stay pending.
 */
export function markInitializationOptionsApplied(options: Record<string, unknown>): void {
  sentInitializationOptions = JSON.stringify(options);
  pendingInitializationOptionsChange =
    JSON.stringify(initializationOptionsPayload().options) !== sentInitializationOptions;
}

async function createLspClient(
  getAcceptedEulaHash: AcceptedEulaHashProvider,
  launchedServerState: LaunchedServerState,
): Promise<{ client: LanguageClient; initializationOptions: Record<string, unknown> } | null> {
  const initializationOptions = buildInitializationOptions();
  const clientOptions: LanguageClientOptions = {
    documentSelector: buildDocumentSelector(),
    progressOnInitialization: true,
    outputChannel: getOutputChannel(),
    initializationOptions,
    middleware: middleware,
    markdown: {
      supportHtml: true,
    },
  };
  const serverOptions = getServerOptions(launchedServerState, getAcceptedEulaHash);
  if (!serverOptions) return null;
  // eslint-disable-next-line prefer-const -- initialized after LanguageClient construction
  let defaultErrorHandler: ErrorHandler | undefined;
  const delegate = (): ErrorHandler => {
    if (!defaultErrorHandler) {
      throw new Error('Language client error handler used before initialization');
    }
    return defaultErrorHandler;
  };
  // A retry resends the same payload, so it can only fail the same way. Prevent the language
  // client's automatic retry, but let the failure propagate to the extension's lifecycle so an
  // open setup panel can leave its loading state.
  clientOptions.initializationFailedHandler = (error) => {
    // Only a response is the server's own verdict. A connection that never came up, or timed out,
    // is a startup failure that the launch attempt classifies more accurately.
    if (error instanceof ResponseError) {
      launchedServerState.initializationRejection = logInitializationRejection(error);
    }
    return false;
  };
  clientOptions.errorHandler = {
    error: (error, message, count) => delegate().error(error, message, count),
    closed: async () => {
      if (await shouldSuppressRestart(launchedServerState, LAUNCHED_SERVER_EXIT_WAIT_MS)) {
        return { action: CloseAction.DoNotRestart, handled: true };
      }
      return delegate().closed();
    },
  };
  const client = new LanguageClient(
    LANGUAGE_CLIENT_ID,
    extensionDisplayName(),
    serverOptions,
    clientOptions,
  );
  defaultErrorHandler = client.createDefaultErrorHandler();
  return { client, initializationOptions };
}

function getUserJvmOptions(): string[] {
  return configOption<string[]>(OPT_JVM_ARGS) ?? [];
}

function buildLaunchEnvironment(
  baseEnv: NodeJS.ProcessEnv,
  configuredProxyOptions: string[],
  extraOptions: string[],
  dataSharing: string,
  region: string | undefined,
): NodeJS.ProcessEnv {
  const env = { ...baseEnv };
  // VS Code launch settings override inherited launcher defaults; additionalJvmArgs stays strongest.
  const jvmOptions = [...configuredProxyOptions, ...extraOptions];
  if (jvmOptions.length > 0) {
    const option = 'IJ_JAVA_OPTIONS';
    const current = env[option] ?? '';
    const extra = jvmOptions.map(shellQuoteIfNeeded).join(' ');
    env[option] = current ? `${current} ${extra}` : extra;
  }
  // the launcher's debug log goes to stdout, which is the protocol channel in --stdio mode
  delete env.IJ_LAUNCHER_DEBUG;
  delete env.INTELLIJ_DATA_SHARING;
  if (dataSharing !== 'none') {
    env.INTELLIJ_DATA_SHARING = dataSharing; // 'full' | 'anonymous'
  }

  delete env.INTELLIJ_REGION;
  if (region) {
    env.INTELLIJ_REGION = region;
  }
  return env;
}

function shellQuoteIfNeeded(arg: string): string {
  // No quoting needed
  if (/^[a-zA-Z0-9._=:/@-]+$/.test(arg)) {
    return arg;
  }
  // Escape special characters
  const escaped = arg.replace(/(["\\$`])/g, '\\$1');
  return `"${escaped}"`;
}

function connectToPort(port: number, timeoutMs: number): Promise<net.Socket> {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ port });

    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error(`Timed out connecting to port ${port}`));
    }, timeoutMs);

    const cleanup = () => {
      clearTimeout(timer);
      socket.removeAllListeners();
    };

    socket.once('connect', () => {
      cleanup();
      resolve(socket);
    });

    socket.once('error', (err) => {
      cleanup();
      reject(err);
    });
  });
}
