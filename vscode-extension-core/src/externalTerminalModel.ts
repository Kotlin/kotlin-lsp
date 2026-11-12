// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { Socket } from 'node:net';
import * as path from 'node:path';

/**
 * The OS window half of an `externalTerminal` launch, with the editor taken out (see `externalTerminal.ts` for the
 * wiring). The server runs the program, so the window runs a relay: a Node script that connects to a local socket
 * of the extension. The relay writes what the socket sends to its stdout, and it sends the lines that the user
 * types to the socket. The relay runs on the Node of VS Code (`process.execPath` with `ELECTRON_RUN_AS_NODE=1`).
 */

/**
 * The relay script. Its argument is the socket path. When the extension closes the socket, the relay keeps the
 * window open until the user presses Enter, so the last output stays readable.
 */
export const RELAY_SCRIPT = `const socket = require('net').connect(process.argv[2]);
socket.pipe(process.stdout);
process.stdin.pipe(socket);
socket.on('error', (e) => process.stdout.write('\\n' + e.message + '\\n'));
socket.on('close', () => {
  process.stdin.unpipe(socket);
  process.stdout.write('\\nPress Enter to close the window.');
  process.stdin.once('data', () => process.exit(0));
});
`;

/** The settings value `terminal.external.*Exec` that VS Code uses as the default for each OS. */
export function defaultTerminalExec(platform: NodeJS.Platform): string {
  if (platform === 'darwin') return 'Terminal.app';
  if (platform === 'win32') return 'cmd.exe';
  return 'x-terminal-emulator';
}

/** The key of the VS Code setting in `terminal.external` that names the terminal app of [platform]. */
export function terminalExecSetting(
  platform: NodeJS.Platform,
): 'osxExec' | 'windowsExec' | 'linuxExec' {
  if (platform === 'darwin') return 'osxExec';
  if (platform === 'win32') return 'windowsExec';
  return 'linuxExec';
}

/** The local socket of one launch: a named pipe on Windows, a Unix domain socket in [tmpDir] elsewhere. */
export function relaySocketPath(options: {
  platform: NodeJS.Platform;
  tmpDir: string;
  id: string;
}): string {
  if (options.platform === 'win32') return `\\\\.\\pipe\\intellij-run-${options.id}`;
  return path.join(options.tmpDir, `intellij-run-${options.id}.sock`);
}

/** What runs the relay: the Node of VS Code, the relay script, and the socket path. */
export interface RelayCommand {
  nodePath: string;
  relayPath: string;
  socketPath: string;
}

/**
 * The text of the macOS `.command` file that runs the relay. `open -a` starts the terminal app without the
 * environment of the extension, so the file sets `ELECTRON_RUN_AS_NODE` itself. `exec` replaces the shell, so the
 * file can be deleted after the relay connects.
 */
export function macCommandFile(relay: RelayCommand): string {
  const quoted = [relay.nodePath, relay.relayPath, relay.socketPath].map(shellQuote).join(' ');
  return `#!/bin/sh\nELECTRON_RUN_AS_NODE=1 exec ${quoted}\n`;
}

/** The process that opens the OS window. */
export interface TerminalSpawn {
  command: string;
  args: string[];
  /** `cmd.exe` parses its own command line, so Node must not quote the arguments (as VS Code does). */
  windowsVerbatimArguments?: boolean;
}

/**
 * The process that opens the OS window, in the form that VS Code uses for its own external terminal. On macOS, the
 * window runs [commandFile] (see [macCommandFile]). On Windows and Linux, the window inherits the environment of
 * the spawn, so `ELECTRON_RUN_AS_NODE` goes there.
 */
export function terminalSpawn(options: {
  platform: NodeJS.Platform;
  exec: string;
  title: string;
  relay: RelayCommand;
  commandFile: string;
}): TerminalSpawn {
  const { platform, exec, title, relay } = options;
  if (platform === 'darwin') return { command: 'open', args: ['-a', exec, options.commandFile] };
  const relayArgs = [relay.nodePath, relay.relayPath, relay.socketPath];
  if (platform === 'win32') {
    return {
      command: 'cmd.exe',
      args: [
        '/c',
        'start',
        `"${title.replace(/"/g, '')}"`,
        `"${exec}"`,
        ...windowsShellArgs(exec, relayArgs),
      ],
      windowsVerbatimArguments: true,
    };
  }
  // gnome-terminal takes the command after `-x`; the others (xterm, konsole, x-terminal-emulator) after `-e`.
  return { command: exec, args: [exec.includes('gnome-terminal') ? '-x' : '-e', ...relayArgs] };
}

/**
 * The arguments that make the Windows terminal [exec] run [relayArgs]. PowerShell gets the call as
 * `-EncodedCommand` (Base64 of UTF-16LE), so no quoting of `cmd.exe` or PowerShell applies to the paths. Every other
 * terminal gets the `cmd.exe` syntax, as in VS Code.
 */
function windowsShellArgs(exec: string, relayArgs: string[]): string[] {
  if (/^(pwsh|powershell)(\.exe)?$/i.test(path.win32.basename(exec))) {
    const call = `& ${relayArgs.map((arg) => `'${arg.replace(/'/g, "''")}'`).join(' ')}`;
    return ['-NoProfile', '-EncodedCommand', Buffer.from(call, 'utf16le').toString('base64')];
  }
  return ['/c', `""${relayArgs.join('" "')}""`];
}

/**
 * A sender that sends each value only after the previous send has settled, so the values arrive in their order. A
 * DAP server can handle requests concurrently, and stdin must get the typed text in order. A failed send goes to
 * [onError] and does not stop the next one.
 */
export function serialized<T>(
  send: (value: T) => PromiseLike<unknown>,
  onError: (e: unknown) => void,
): (value: T) => void {
  let tail: Promise<unknown> = Promise.resolve();
  return (value) => {
    tail = tail.then(() => send(value)).then(undefined, onError);
  };
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

/** What a [RelayStream] does in the debug session. */
export interface RelaySession {
  /** Sends text that the user typed to the program. */
  sendInput(text: string): void;
  /** Stops the session. */
  stop(): void;
}

/**
 * The stream between the session and the relay of one launch. The output written before the relay connects is
 * buffered. The text that the relay sends goes to the program. When the relay closes before [end], the user closed
 * the window or pressed Ctrl+C, so the session stops, as a program that ran in the window (the `runInTerminal`
 * launch before LSP-1965) ended with it.
 */
export class RelayStream {
  private socket: Socket | undefined;
  private pending: string[] = [];
  private ended = false;

  constructor(private readonly session: RelaySession) {}

  get isConnected(): boolean {
    return this.socket !== undefined;
  }

  write(chunk: string): void {
    if (this.socket) {
      if (!this.socket.writableEnded) this.socket.write(chunk);
    } else if (!this.ended) {
      this.pending.push(chunk);
    }
  }

  /** Ends the stream. A relay that has not connected yet still gets the buffered output first. */
  end(): void {
    this.ended = true;
    this.socket?.end();
  }

  /** No relay can connect anymore: drops the buffer and takes no more output. */
  fail(): void {
    this.ended = true;
    this.pending = [];
  }

  connect(socket: Socket): void {
    this.socket = socket;
    socket.setEncoding('utf8');
    socket.on('data', (text: string) => this.session.sendInput(text));
    socket.on('error', () => undefined);
    socket.on('close', () => {
      if (!this.ended) this.session.stop();
    });
    this.pending.forEach((chunk) => socket.write(chunk));
    this.pending = [];
    if (this.ended) socket.end();
  }
}
