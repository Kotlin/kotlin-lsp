import {
  type CancellationToken,
  debug,
  type DebugConfiguration,
  type DebugSession,
  type Disposable,
  type WorkspaceFolder,
} from 'vscode';

export interface TrackedDebugConfiguration extends DebugConfiguration {
  jvmTestRunToken: string;
}

interface DapMessage {
  type: string;
  event?: string;
  body?: { output?: string; exitCode?: number };
}

export function launchAndCollectOutput(
  folder: WorkspaceFolder | undefined,
  config: TrackedDebugConfiguration,
  onOutput: (text: string) => void,
  noDebug: boolean,
  token: CancellationToken,
): Promise<number | undefined> {
  return new Promise((resolve, reject) => {
    let exitCode: number | undefined;
    const subscriptions: Disposable[] = [];
    // Single exit path: the session can end in three ways, and each has to unsubscribe.
    const settle = (outcome: () => void): void => {
      for (const subscription of subscriptions) subscription.dispose();
      subscriptions.length = 0;
      outcome();
    };
    const isOurs = (session: DebugSession): boolean =>
      (session.configuration as TrackedDebugConfiguration).jvmTestRunToken ===
      config.jvmTestRunToken;

    subscriptions.push(
      debug.registerDebugAdapterTrackerFactory(config.type, {
        createDebugAdapterTracker(session) {
          if (!isOurs(session)) return undefined;
          return {
            onDidSendMessage(message: DapMessage) {
              if (message.type !== 'event') return;
              if (message.event === 'output' && typeof message.body?.output === 'string') {
                onOutput(message.body.output);
              } else if (message.event === 'exited') {
                exitCode = message.body?.exitCode;
              }
            },
          };
        },
      }),
      debug.onDidTerminateDebugSession((session) => {
        if (isOurs(session)) settle(() => resolve(exitCode));
      }),
      // Cancelling has to kill the process, not just stop waiting for it: the promise only settles
      // on termination, and the session outlives the extension's interest in it either way.
      debug.onDidStartDebugSession((session) => {
        if (!isOurs(session)) return;
        if (token.isCancellationRequested) void debug.stopDebugging(session);
        else
          subscriptions.push(
            token.onCancellationRequested(() => void debug.stopDebugging(session)),
          );
      }),
    );

    debug.startDebugging(folder, config, { noDebug }).then(
      (started) => {
        if (!started) settle(() => reject(new Error('Failed to start the test process.')));
      },
      (e) => settle(() => reject(e)),
    );
  });
}
