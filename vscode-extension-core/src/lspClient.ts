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
} from './extension';
import { clearBuildError, setBuildError, updateLspStatusBar } from './statusBar';
import { middleware } from './middleware';
import * as readline from 'node:readline';
import { type Readable } from 'node:stream';
import { ensureServerLauncher } from './serverBundleDownload';
import {
  registerCopyToClipboardHandler,
  registerIntellijExtensionsInitOption,
} from './intellijExtensions';

interface ExtensionPackageJson {
  displayName?: string;
  contributes?: {
    languages?: Array<{ id: string }>;
  };
}

const BUNDLED_SERVER_START_TIMEOUT_MS = 60_000;
const BUNDLED_SERVER_CONNECTION_TIMEOUT_MS = 10_000;
const LOCAL_SERVER_CONNECTION_TIMEOUT_MS = 10_000;
const CONNECTION_RETRY_DELAY_MS = 100;

const LANGUAGE_CLIENT_ID = 'intellij';
const OPT_DEV_SERVER_PORT = 'intellij.dev.serverPort';
const OPT_DEV_SERVER_TIMEOUT = 'intellij.dev.serverTimeoutMs';
const OPT_LOG_LAUNCH = 'intellij.dev.logLaunch';
const OPT_JVM_ARGS = 'intellij.additionalJvmArgs';
const OPT_DEFAULT_WORKSPACE_SDK = 'intellij.jdkForSymbolResolution';
const OPT_BUILD_TOOL = 'intellij.buildTool';
const OPT_DATA_SHARING = 'intellij.dataSharing';
const OPT_PROJECTS = 'intellij.projects';

const INDEX_DIR_STATE_KEY = 'jetbrains.intellij.indexDir';

let _client: LanguageClient | undefined;
let startLspClientPromise: Promise<void> | undefined;
let restartRequestedDuringStart = false;
let bundledServerLauncherCache: { key: string; promise: Promise<string> } | undefined;

type ImportLogParams =
  | { type: 1 | 2 | 3; message: string; failed?: false; succeeded?: false }
  | { type: 1; message: string; failed: true; succeeded?: false; tool?: string }
  | { type: 3; message: string; failed?: false; succeeded: true };

const importLogNotification = new NotificationType<ImportLogParams>('intellij/importLog');

const clientSubscriptions: ((client: LanguageClient, stateChange: StateChangeEvent) => void)[] = [];

export type InitializationOptionsContributor = () => Record<string, unknown>;

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

export function initLspClient(getAcceptedEulaHash: AcceptedEulaHashProvider): void {
  registerIntellijExtensionsInitOption();
  getContext().subscriptions.push(
    Disposable.create(async () => await stopLspClient()),
    vscode.commands.registerCommand('jetbrains.kotlin.restartLsp', async () => {
      await startLspClient({ getAcceptedEulaHash, restartIfStarting: true });
      await vscode.window.showInformationMessage(extensionDisplayName() + ' restarted');
    }),
    vscode.commands.registerCommand('jetbrains.kotlin.clearCachesAndRestartLsp', async () => {
      await clearCachesAndRestart(getAcceptedEulaHash);
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
async function clearCachesAndRestart(getAcceptedEulaHash: AcceptedEulaHashProvider): Promise<void> {
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
    _client.onDidChangeState((e) => clientSubscriptions.forEach((s) => s(runClient, e))),
  );

  try {
    await runClient.start();
    registerImportLogHandler(runClient);
    registerCopyToClipboardHandler(runClient);
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
  const cacheKey = `${context.extensionPath}\0${context.globalStorageUri.fsPath}`;
  if (bundledServerLauncherCache?.key !== cacheKey) {
    bundledServerLauncherCache = {
      key: cacheKey,
      promise: Promise.resolve(
        vscode.window.withProgress(
          {
            location: vscode.ProgressLocation.Notification,
            title: `${extensionDisplayName()}: language server setup`,
          },
          async (progress) => {
            const launcherPath = await ensureServerLauncher({
              extensionPath: context.extensionPath,
              storagePath: context.globalStorageUri.fsPath,
              log: logInfo,
              progress: (update) => progress.report(update),
              allowCachedServerWithoutMetadata: isDevelopment,
            });
            if (isDevelopment) {
              void vscode.window.showInformationMessage(
                `${extensionDisplayName()}: development mode is using language server ${launcherPath}`,
              );
            }
            return launcherPath;
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
    throw error;
  }
  if (os.platform() !== 'win32') {
    chmodSync(launcherPath, 0o755);
  }
  return launcherPath;
}

export async function prepareBundledServerLauncher(): Promise<string | undefined> {
  const predefinedPort = configOption<number>(OPT_DEV_SERVER_PORT) ?? -1;
  if (predefinedPort !== -1) return undefined;
  return ensureBundledServerLauncher();
}

function getServerOptions(): ServerOptions {
  const predefinedPort = configOption<number>(OPT_DEV_SERVER_PORT) ?? -1;
  if (predefinedPort == -1) {
    return getStreamInfoForBundledServer;
  } else {
    return () =>
      getStreamInfoForRunningServer(
        predefinedPort,
        configOption<number>(OPT_DEV_SERVER_TIMEOUT) ?? LOCAL_SERVER_CONNECTION_TIMEOUT_MS,
      );
  }
}

async function getStreamInfoForBundledServer(): Promise<StreamInfo> {
  const serverProcess = await startBundledServer();
  try {
    const port = await getPortForBundledServer(serverProcess);
    return await getStreamInfoForRunningServer(port, BUNDLED_SERVER_CONNECTION_TIMEOUT_MS);
  } catch (e) {
    serverProcess.kill();
    throw e;
  }
}

async function startBundledServer(): Promise<ChildProcessByStdio<null, Readable, Readable>> {
  const debugLaunch = configOption<boolean>(OPT_LOG_LAUNCH) ?? false;
  const launcherPath = await ensureBundledServerLauncher();

  const context = getContext();
  const args: string[] = [];
  args.push('--socket', '0');
  if (context.storageUri) {
    args.push('--system-path', context.storageUri.fsPath);
  }
  const userJvmOptions = getUserJvmOptions();
  const jvmOptions = buildJvmOptionsEnv(process.env, userJvmOptions);
  const env: NodeJS.ProcessEnv = debugLaunch
    ? {
        ...jvmOptions,
        IJ_LAUNCHER_DEBUG: '1',
      }
    : jvmOptions;
  const dataSharing = configOption<string>(OPT_DATA_SHARING) ?? 'none';
  if (dataSharing !== 'none') {
    env.INTELLIJ_DATA_SHARING = dataSharing; // 'full' | 'anonymous'
  }

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

function getPortForBundledServer(
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
    }, BUNDLED_SERVER_START_TIMEOUT_MS);

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

function buildJvmOptionsEnv(baseEnv: NodeJS.ProcessEnv, extraOptions: string[]): NodeJS.ProcessEnv {
  if (extraOptions.length === 0) {
    return baseEnv;
  }
  const OPTION = 'IJ_JAVA_OPTIONS';
  const env: NodeJS.ProcessEnv = { ...baseEnv };

  const current = env[OPTION] ?? '';
  const extra = extraOptions.map(shellQuoteIfNeeded).join(' ');
  env[OPTION] = current ? `${current} ${extra}` : extra;

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
