// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { chmod, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { createServer, type Server } from 'node:net';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { debug, type DebugSession, window, workspace } from 'vscode';
import { errorMessage } from './buildTaskModel';
import { getOutputChannel } from './extension';
import {
  defaultTerminalExec,
  macCommandFile,
  RELAY_SCRIPT,
  RelayStream,
  relaySocketPath,
  serialized,
  terminalExecSetting,
  terminalSpawn,
} from './externalTerminalModel';

/** The JetBrains DAP request that writes text to the stdin of the program that the server runs. */
const PROCESS_INPUT_REQUEST = 'intellij/processInput';

/**
 * A function that sends text to the stdin of the program of [session], in the order of the calls. A failed send goes
 * to [onError].
 */
export function processInputSender(
  session: DebugSession,
  onError: (e: unknown) => void,
): (text: string) => void {
  return serialized(
    (text: string) => session.customRequest(PROCESS_INPUT_REQUEST, { text }),
    onError,
  );
}

/**
 * The OS terminal window of an `externalTerminal` launch. The window runs a relay (see `externalTerminalModel.ts`)
 * that connects to a local socket. The stream goes to the socket, and each line that the user types goes to the
 * server as an `intellij/processInput` request. [RelayStream] holds the stream logic; this class opens the window.
 */
export class ExternalRunTerminal {
  private readonly stream: RelayStream;
  private readonly server: Server = createServer((socket) => {
    // One relay per launch: the first connection closes the server, and the files are not needed anymore.
    this.server.close();
    this.removeTempDir();
    this.stream.connect(socket);
  });
  private tempDir: string | undefined;

  constructor(private readonly session: DebugSession) {
    const sendInput = processInputSender(session, (e) =>
      this.stream.write(`\r\n${inputFailedMessage(e)}\r\n`),
    );
    this.stream = new RelayStream({ sendInput, stop: () => void debug.stopDebugging(session) });
    // A relay that never connects (the window did not open) must not keep the extension host alive.
    this.server.unref();
    this.open().catch((e: unknown) => this.failed(e));
  }

  write(chunk: string): void {
    this.stream.write(chunk);
  }

  end(): void {
    this.stream.end();
  }

  private async open(): Promise<void> {
    const platform = process.platform;
    this.tempDir = await mkdtemp(path.join(tmpdir(), 'intellij-run-'));
    const relayPath = path.join(this.tempDir, 'relay.cjs');
    await writeFile(relayPath, RELAY_SCRIPT);
    const socketPath = relaySocketPath({
      platform,
      tmpDir: this.tempDir,
      id: randomUUID().slice(0, 8),
    });
    await new Promise<void>((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen(socketPath, resolve);
    });
    const relay = { nodePath: process.execPath, relayPath, socketPath };
    // The title of a macOS window is the name of the file.
    const commandFile = path.join(
      this.tempDir,
      `${this.session.name.replace(/[^\w .-]/g, '_')}.command`,
    );
    if (platform === 'darwin') {
      await writeFile(commandFile, macCommandFile(relay));
      await chmod(commandFile, 0o755);
    }
    const exec =
      workspace.getConfiguration('terminal.external').get<string>(terminalExecSetting(platform)) ||
      defaultTerminalExec(platform);
    const { command, args, windowsVerbatimArguments } = terminalSpawn({
      platform,
      exec,
      title: this.session.name,
      relay,
      commandFile,
    });
    const child = spawn(command, args, {
      detached: true,
      stdio: 'ignore',
      env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' },
      windowsVerbatimArguments,
    });
    child.on('error', (e) => this.failed(e));
    // A Linux terminal app can live as long as its window, so an exit after the relay connected is no failure.
    child.on('exit', (code) => {
      if (code && !this.stream.isConnected)
        this.failed(new Error(`"${command} ${args.join(' ')}" exited with code ${code}`));
    });
    child.unref();
  }

  private failed(e: unknown): void {
    const message = `Cannot open an external terminal for "${this.session.name}": ${errorMessage(e)}`;
    getOutputChannel().appendLine(`[run] ${message}`);
    void window.showErrorMessage(`${message}. The output is in the Debug Console.`);
    this.stream.fail();
    this.server.close();
    this.removeTempDir();
  }

  private removeTempDir(): void {
    if (this.tempDir) void rm(this.tempDir, { recursive: true, force: true });
    this.tempDir = undefined;
  }
}

/** The line that a terminal shows when the input did not reach the program. */
export function inputFailedMessage(e: unknown): string {
  return `The input did not reach the program: ${errorMessage(e)}`;
}
