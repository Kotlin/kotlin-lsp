// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  type DebugAdapterTracker,
  type DebugAdapterTrackerFactory,
  type DebugSession,
  EventEmitter,
  type Pseudoterminal,
  type Terminal,
  debug,
  env,
  window,
} from 'vscode';
import type { ConsoleKind } from './dap';
import { ExternalRunTerminal, inputFailedMessage, processInputSender } from './externalTerminal';
import {
  showsStreamInOsWindow,
  showsStreamInTerminal,
  terminalChunk,
  TerminalInput,
} from './runTerminalModel';

/**
 * The terminal of a terminal launch. The server runs the program itself and streams its output as `output` events
 * (`RunHandleProcessHandler` on the server turns the build tool's `RunTaskEvent`s into them), so for a `console` of
 * `integratedTerminal` the client renders that stream in a terminal of its own: an adapter tracker reads the events
 * off the session and writes them into a pseudoterminal. Nothing runs in the terminal: the typed lines go to the
 * program as `intellij/processInput` requests while the session runs (see [TerminalInput]), and Ctrl+C stops the
 * session. Closing the terminal only hides the output. For `externalTerminal` the tracker writes the stream into an
 * OS window instead, which also sends the typed lines to the program (`externalTerminal.ts`). The window of an
 * ended session waits for Enter, and the next run opens a new one. A remote window keeps the integrated terminal:
 * the remote host can have no display.
 *
 * One launch, one terminal: a new session whose configuration has the name of an ended one disposes the old
 * terminal, so repeated runs of the same configuration do not pile terminals up — the fresh one starts with a clean
 * scrollback, where VS Code's own `runInTerminal` would have reused the shell.
 */
export function createRunOutputTerminalFactory(): DebugAdapterTrackerFactory {
  // Terminals of ended sessions, by configuration name; the next run of the configuration replaces its terminal.
  const idleByName = new Map<string, RunOutputTerminal>();
  return {
    createDebugAdapterTracker(session: DebugSession): DebugAdapterTracker | undefined {
      const console = (session.configuration as { console?: ConsoleKind }).console;
      if (!showsStreamInTerminal(console)) return undefined;
      if (showsStreamInOsWindow(console, env.remoteName)) return externalTerminalTracker(session);
      let terminal: RunOutputTerminal | undefined;
      const open = (): RunOutputTerminal => {
        if (!terminal) {
          idleByName.get(session.name)?.dispose();
          idleByName.delete(session.name);
          terminal = new RunOutputTerminal(session);
        }
        return terminal;
      };
      return {
        // Open the terminal at the start, not at the first output line: the user sees where the output will land
        // while the build tool is still compiling silently.
        onWillStartSession: () => void open(),
        onDidSendMessage: (message: unknown) => {
          const chunk = terminalChunk(message);
          if (chunk !== undefined) open().write(chunk);
        },
        onWillStopSession: () => {
          if (!terminal) return;
          terminal.sessionEnded();
          idleByName.set(session.name, terminal);
        },
      };
    },
  };
}

/** The tracker of an `externalTerminal` session: the OS window opens at the start and ends with the session. */
function externalTerminalTracker(session: DebugSession): DebugAdapterTracker {
  let terminal: ExternalRunTerminal | undefined;
  return {
    onWillStartSession: () => {
      terminal = new ExternalRunTerminal(session);
    },
    onDidSendMessage: (message: unknown) => {
      const chunk = terminalChunk(message);
      if (chunk !== undefined) terminal?.write(chunk);
    },
    onWillStopSession: () => terminal?.end(),
  };
}

/**
 * A terminal that renders a stream: writes buffer until VS Code opens the pseudoterminal, then pass through. The
 * keys typed while the session runs go to the program line by line (see [TerminalInput]).
 */
class RunOutputTerminal {
  private readonly writeEmitter = new EventEmitter<string>();
  // Chunks written before `open`; a pseudoterminal drops what is fired earlier.
  private pending: string[] | undefined = [];
  private closed = false;
  private readonly terminal: Terminal;
  private readonly input: TerminalInput;

  constructor(session: DebugSession) {
    this.input = new TerminalInput({
      echo: (text) => this.write(text),
      send: processInputSender(session, (e) => this.write(`\r\n${inputFailedMessage(e)}\r\n`)),
      interrupt: () => void debug.stopDebugging(session),
    });
    const pty: Pseudoterminal = {
      onDidWrite: this.writeEmitter.event,
      open: () => {
        const buffered = this.pending;
        this.pending = undefined;
        buffered?.forEach((chunk) => this.writeEmitter.fire(chunk));
      },
      close: () => {
        this.closed = true;
      },
      handleInput: (data: string) => this.input.handle(data),
    };
    this.terminal = window.createTerminal({ name: session.name, pty });
    // Reveal the panel but keep the keyboard in the editor: the user clicks the terminal to type input.
    this.terminal.show(true);
  }

  sessionEnded(): void {
    this.input.end();
  }

  write(chunk: string): void {
    if (this.closed) return;
    if (this.pending !== undefined) this.pending.push(chunk);
    else this.writeEmitter.fire(chunk);
  }

  dispose(): void {
    this.terminal.dispose();
  }
}
