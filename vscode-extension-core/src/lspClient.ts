import * as vscode from 'vscode';
import { workspace } from 'vscode';
import {
  Disposable,
  LanguageClient,
  LanguageClientOptions,
  NotificationType,
  ServerOptions,
  State,
  StateChangeEvent,
  StreamInfo,
} from 'vscode-languageclient/node';
import { chmodSync } from 'fs';
import { rm } from 'node:fs/promises';
import * as net from 'node:net';
import * as os from 'node:os';
import { type ChildProcessByStdio, spawn } from 'node:child_process';
import {
  type AcceptedEulaHashProvider,
  getBuildOutputChannel,
  getContext,
  getOutputChannel,
  logInfo,
  revealBuildLog,
} from './extension';
import { runWithEulaGate } from './eulaGate';
import { clearBuildError, setBuildError, updateLspStatusBar } from './statusBar';
import { middleware } from './middleware';
import * as readline from 'node:readline';
import { type Readable } from 'node:stream';
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
import {
  registerChooseActionMenuHandler,
  registerCopyToClipboardHandler,
  registerIntellijExtensionsInitOption,
} from './intellijExtensions';
import {
  handleCancelledServerDownload,
  handleServerDownloadChecksumMismatch,
} from './serverDownloadRecovery';

interface ExtensionPackageJson {
  name?: string;
  displayName?: string;
  contributes?: {
    languages?: Array<{ id: string }>;
  };
}

const LAUNCHED_SERVER_START_TIMEOUT_MS = 60_000;
const LAUNCHED_SERVER_CONNECTION_TIMEOUT_MS = 10_000;
const LOCAL_SERVER_CONNECTION_TIMEOUT_MS = 10_000;
const CONNECTION_RETRY_DELAY_MS = 100;

const LANGUAGE_CLIENT_ID = 'intellij';
const OPT_DEV_SERVER_PORT = 'intellij.dev.serverPort';
const OPT_DEV_SERVER_TIMEOUT = 'intellij.dev.serverTimeoutMs';
const OPT_SERVER_PATH = 'intellij.serverPath';
const OPT_LOG_LAUNCH = 'intellij.dev.logLaunch';
const OPT_JVM_ARGS = 'intellij.additionalJvmArgs';
const OPT_DEFAULT_WORKSPACE_SDK = 'intellij.jdkForSymbolResolution';
const OPT_BUILD_TOOL = 'intellij.buildTool';
const OPT_DATA_SHARING = 'intellij.dataSharing';
const OPT_REGION = 'intellij.region';
const OPT_PROJECTS = 'intellij.projects';
const OPT_DISABLE_ROCKS_DB_WAL = 'intellij.disableRocksDBWriteAheadLog';

const INDEX_DIR_STATE_KEY = 'jetbrains.intellij.indexDir';

let _client: LanguageClient | undefined;
let startLspClientPromise: Promise<void> | undefined;
let restartRequestedDuringStart = false;
let bundledServerLauncherCache: { key: string; promise: Promise<string> } | undefined;
let bundledServerSetupPhase: ServerBundlePhase = 'downloading';
let configuredClientFeatureFactories: ClientFeatureFactory[] = [];

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

const clientSubscriptions: ((client: LanguageClient, stateChange: StateChangeEvent) => void)[] = [];

export type InitializationOptionsContributor = () => Record<string, unknown>;
export type { ClientFeatureFactory } from './clientFeatureFactories';

/**
 * An externally configured project passed to the server via initialization options.
 * Mirrors the `intellij.projects` setting and the server-side `ConfiguredProject` model.
 */
export interface ConfiguredProject {
  /** Build tool / project type, e.g. "gradle", "maven", "bazel", "json". */
  type: string;
  /** URI pointing to the project's build file or workspace root. */
  path: string;
  /** Maven only: extra environment variables for the import process. */
  env?: Record<string, string>;
  /** Maven only: JVM system properties for the import process. */
  'system-properties'?: Record<string, string>;
  /** Maven only: path to the JDK home used to run the import. */
  'java-home'?: string;
  /** Bazel only: path to the Bazel project file, relative to the workspace root. */
  'project-path'?: string;
}

const initializationOptionsContributors: InitializationOptionsContributor[] = [];

export function registerInitializationOptionsContributor(
  contributor: InitializationOptionsContributor,
): void {
  initializationOptionsContributors.push(contributor);
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
  configuredClientFeatureFactories = [...clientFeatureFactories];
  registerIntellijExtensionsInitOption();
  // TODO: Send the updated region to the backend when runtime region updates are supported.
  getContext().subscriptions.push(
    Disposable.create(async () => await stopLspClient()),
    vscode.commands.registerCommand('jetbrains.kotlin.restartLsp', async () => {
      const restarted = await runWithEulaGate({
        checkEulaAccepted,
        action: () => startLspClient({ getAcceptedEulaHash, restartIfStarting: true }),
      });
      if (!restarted) return;
      await vscode.window.showInformationMessage(extensionDisplayName() + ' restarted');
    }),
    vscode.commands.registerCommand('jetbrains.kotlin.clearCachesAndRestartLsp', async () => {
      await clearCachesAndRestart({ getAcceptedEulaHash, checkEulaAccepted });
    }),
  );
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
        ? `The index directory will be deleted and rebuilt from scratch:\n${indexDir}`
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
        await vscode.window.showErrorMessage(`Failed to clear caches at ${indexDir}: ${message}`);
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
    if (startLspClientPromise === promise) startLspClientPromise = undefined;
  });
  startLspClientPromise = promise;
  return promise;
}

async function doStartLspClient(getAcceptedEulaHash: AcceptedEulaHashProvider): Promise<void> {
  const runClient = await createLspClient(getAcceptedEulaHash);
  if (!runClient) return;
  await stopLspClient();
  _client = runClient;
  getContext().subscriptions.push(
    _client.onDidChangeState((e) => {
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

  try {
    await startClientWithFeatures(runClient, configuredClientFeatureFactories);
    registerImportLogHandler(runClient);
    registerCopyToClipboardHandler(runClient);
    registerChooseActionMenuHandler(runClient);
  } catch (e) {
    if (
      e instanceof LanguageServerStartupError &&
      e.code === LanguageServerStartupError.LICENSE_ERROR_CODE
    ) {
      void vscode.window.showErrorMessage(
        `"${extensionDisplayName()}" could not properly start the language server.`,
        {
          modal: true,
          detail:
            'The bundled language server build has expired. Update the extension and try again.',
        },
      );

      return;
    }

    throw e;
  }
}

export async function stopLspClient(): Promise<void> {
  if (!_client) return;
  const client = _client;
  _client = undefined;
  updateLspStatusBar();
  if (!client.needsStop()) {
    return;
  }
  try {
    await client.stop();
  } catch (error) {
    if (!isWriteAfterEndError(error)) throw error;
  }
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
                  `${extensionDisplayName()}: development mode is using language server ${launcherPath}`,
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
  await stopLspClient();
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

function getServerOptions(): ServerOptions {
  const predefinedPort = configOption<number>(OPT_DEV_SERVER_PORT) ?? -1;
  if (predefinedPort !== -1) {
    return () =>
      getStreamInfoForRunningServer(
        predefinedPort,
        configOption<number>(OPT_DEV_SERVER_TIMEOUT) ?? LOCAL_SERVER_CONNECTION_TIMEOUT_MS,
      );
  }
  return () => getStreamInfoForLaunchedServer(configuredServerLauncherPath());
}

function configuredServerLauncherPath(): string | undefined {
  const serverPath = configOption<string>(OPT_SERVER_PATH);
  return serverPath === undefined ? undefined : serverLauncherPath(serverPath);
}

async function getStreamInfoForLaunchedServer(launcherPath?: string): Promise<StreamInfo> {
  const serverProcess = await startServer(launcherPath);
  try {
    const port = await getPortForLaunchedServer(serverProcess);
    return await getStreamInfoForRunningServer(port, LAUNCHED_SERVER_CONNECTION_TIMEOUT_MS);
  } catch (e) {
    serverProcess.kill();
    throw e;
  }
}

async function startServer(
  configuredLauncherPath?: string,
): Promise<ChildProcessByStdio<null, Readable, Readable>> {
  const debugLaunch = configOption<boolean>(OPT_LOG_LAUNCH) ?? false;
  const launcherPath = configuredLauncherPath ?? (await ensureBundledServerLauncher());

  const context = getContext();
  const args: string[] = [];
  args.push('--socket', '0');
  if (context.storageUri) {
    args.push('--system-path', context.storageUri.fsPath);
  }
  const userJvmOptions = getUserJvmOptions();
  const dataSharing = dataSharingLevel(configOption(OPT_DATA_SHARING)) ?? 'none';
  const region = specifiedRegion(configOption(OPT_REGION));
  const env = buildLaunchEnvironment(process.env, userJvmOptions, debugLaunch, dataSharing, region);

  logInfo('Starting language server');
  logInfo(`  command: ${launcherPath}`);
  logInfo(`  args   : ${JSON.stringify(args)}`);
  logInfo(`  VM opts: ${JSON.stringify(userJvmOptions)}`);
  if (debugLaunch) {
    logInfo(`  env: ${JSON.stringify(env)}`);
  }
  logInfo('');

  const serverProcess = spawn(launcherPath, args, {
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  if (debugLaunch) {
    const rl = readline.createInterface({
      input: serverProcess.stdout,
      terminal: false,
    });
    rl.on('line', (line: string) => logInfo(`[stdout] ${line}`));
    serverProcess.once('exit', () => rl.close());

    const rlErr = readline.createInterface({
      input: serverProcess.stderr,
      terminal: false,
    });
    rlErr.on('line', (line: string) => logInfo(`[stderr] ${line}`));
    serverProcess.once('exit', () => rlErr.close());
  }
  return serverProcess;
}

class LanguageServerStartupError extends Error {
  static readonly LICENSE_ERROR_CODE: number = 7;

  constructor(
    public code: number | null,
    public signal: NodeJS.Signals | null,
  ) {
    super(`Language server process exited before announcing port (code=${code}, signal=${signal})`);
  }
}

function getPortForLaunchedServer(
  serverProcess: ChildProcessByStdio<null, Readable, Readable>,
): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const cleanup = () => {
      serverProcess.removeAllListeners('exit');
      serverProcess.removeAllListeners('error');

      clearTimeout(timer);
    };

    const onExit = (code: number | null, signal: NodeJS.Signals | null) => {
      reject(new LanguageServerStartupError(code, signal));
    };

    const timer = setTimeout(() => {
      cleanup();
      serverProcess.kill();
      reject(new Error('Timed out waiting for language server port announcement'));
    }, LAUNCHED_SERVER_START_TIMEOUT_MS);

    const rl = readline.createInterface({
      input: serverProcess.stdout,
      terminal: false,
    });

    rl.on('line', (line: string) => {
      if (line.indexOf('Server is listening on ') >= 0) {
        const pos = line.lastIndexOf(':');
        if (pos > 0) {
          const portString = line.substring(pos + 1);
          const parsedPort = Number(portString);
          if (Number.isInteger(parsedPort)) {
            cleanup();
            rl.close();
            serverProcess.stdout.resume();
            resolve(parsedPort);
          }
        }
      }
    });

    serverProcess.once('error', reject);
    serverProcess.once('exit', onExit);
  });
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

  const supportedSchemes = ['file', 'jar', 'jrt'];
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

/**
 * Assembles the server `initializationOptions` from the current VS Code settings. Read fresh each
 * time, so it reflects edits to e.g. `intellij.projects`. Used both for the initial `initialize` and
 * for the `intellij/reloadWorkspace` request, so a reload picks up settings changes without
 * reopening the folder.
 */
export function buildInitializationOptions(
  getAcceptedEulaHash: AcceptedEulaHashProvider,
): Record<string, unknown> {
  const folders = workspace.workspaceFolders ?? [];
  const builtinInitializationOptions = {
    defaultSdk: configOption(OPT_DEFAULT_WORKSPACE_SDK),
    buildTools: Object.fromEntries(
      folders.map((folder) => [
        folder.uri.toString(),
        configOption<string>(OPT_BUILD_TOOL, folder.uri),
      ]),
    ),
    projects: configOption<ConfiguredProject[]>(OPT_PROJECTS) ?? [],
    disableRocksDBWriteAheadLog: configOption<boolean>(OPT_DISABLE_ROCKS_DB_WAL) ?? false,
    eulaHash: getAcceptedEulaHash(getContext()),
  };
  const contributedInitializationOptions = Object.assign(
    {},
    ...initializationOptionsContributors.map((c) => c()),
  );
  return {
    ...contributedInitializationOptions,
    ...builtinInitializationOptions,
  };
}

async function createLspClient(
  getAcceptedEulaHash: AcceptedEulaHashProvider,
): Promise<LanguageClient | null> {
  const clientOptions: LanguageClientOptions = {
    documentSelector: buildDocumentSelector(),
    progressOnInitialization: true,
    outputChannel: getOutputChannel(),
    initializationOptions: buildInitializationOptions(getAcceptedEulaHash),
    middleware: middleware,
    markdown: {
      supportHtml: true,
    },
  };
  const serverOptions = getServerOptions();
  if (!serverOptions) return null;
  return new LanguageClient(
    LANGUAGE_CLIENT_ID,
    extensionDisplayName(),
    serverOptions,
    clientOptions,
  );
}

function getUserJvmOptions(): string[] {
  return configOption<string[]>(OPT_JVM_ARGS) ?? [];
}

type DataSharingLevel = 'full' | 'anonymous' | 'none';

function dataSharingLevel(value: unknown): DataSharingLevel | undefined {
  return value === 'full' || value === 'anonymous' || value === 'none' ? value : undefined;
}

function specifiedRegion(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 && value !== 'not_set' ? value : undefined;
}

function buildLaunchEnvironment(
  baseEnv: NodeJS.ProcessEnv,
  extraOptions: string[],
  debugLaunch: boolean,
  dataSharing: string,
  region: string | undefined,
): NodeJS.ProcessEnv {
  const env = { ...baseEnv };
  if (extraOptions.length > 0) {
    const option = 'IJ_JAVA_OPTIONS';
    const current = env[option] ?? '';
    const extra = extraOptions.map(shellQuoteIfNeeded).join(' ');
    env[option] = current ? `${current} ${extra}` : extra;
  }
  if (debugLaunch) {
    env.IJ_LAUNCHER_DEBUG = '1';
  }
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
