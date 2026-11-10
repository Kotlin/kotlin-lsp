// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  type DebugAdapterTracker,
  type DebugAdapterTrackerFactory,
  type DebugSession,
  EventEmitter,
  type Pseudoterminal,
  type Terminal,
  type TerminalLink,
  type TerminalLinkProvider,
  debug,
  env,
  window,
} from 'vscode';
import type { ConsoleKind } from './dap';
import { ExternalRunTerminal, inputFailedMessage, processInputSender } from './externalTerminal';
import {
  OutputLines,
  showsStreamInOsWindow,
  showsStreamInTerminal,
  terminalChunk,
  TerminalInput,
} from './runTerminalModel';
import { navigateToTrace, type StackTracePatterns, stackTracePatterns } from './stackTrace';

/** The output of each open run terminal, for the stack trace links. */
const outputByTerminal = new Map<Terminal, OutputLines>();

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
          const opened = new RunOutputTerminal(session, () => {
            if (idleByName.get(session.name) === opened) idleByName.delete(session.name);
          });
          terminal = opened;
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
          if (!terminal.closed) idleByName.set(session.name, terminal);
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
  /** Whether the user closed the terminal. */
  closed = false;
  private readonly terminal: Terminal;
  private readonly output = new OutputLines();
  private readonly input: TerminalInput;

  /** @param onClose called when the user closes the terminal */
  constructor(session: DebugSession, onClose: () => void) {
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
        outputByTerminal.delete(this.terminal);
        onClose();
      },
      handleInput: (data: string) => this.input.handle(data),
    };
    this.terminal = window.createTerminal({ name: session.name, pty });
    outputByTerminal.set(this.terminal, this.output);
    // Reveal the panel but keep the keyboard in the editor: the user clicks the terminal to type input.
    this.terminal.show(true);
  }

  sessionEnded(): void {
    this.input.end();
  }

  write(chunk: string): void {
    if (this.closed) return;
    this.output.append(chunk);
    if (this.pending !== undefined) this.pending.push(chunk);
    else this.writeEmitter.fire(chunk);
  }

  dispose(): void {
    outputByTerminal.delete(this.terminal);
    this.terminal.dispose();
  }
}

interface StackTraceLink extends TerminalLink {
  readonly line: string;
  readonly output: OutputLines;
  readonly patterns: StackTracePatterns;
}

/**
 * Links the stack trace frames in the run terminals to the code, the frames of every language the server has
 * patterns for.
 */
export const stackTraceLinkProvider: TerminalLinkProvider<StackTraceLink> = {
  async provideTerminalLinks({ line, terminal }) {
    const output = outputByTerminal.get(terminal);
    if (!output) return [];
    const patterns = await stackTracePatterns();
    if (!patterns.frames.some((frame) => frame.test(line))) return [];
    const startIndex = line.search(/\S/);
    return [{ startIndex, length: line.trimEnd().length - startIndex, line, output, patterns }];
  },

  async handleTerminalLink({ line, output, patterns }) {
    const isTraceLine = (traceLine: string) =>
      [...patterns.frames, ...patterns.traceLines].some((pattern) => pattern.test(traceLine));
    await navigateToTrace(output.traceEndingWith(line, isTraceLine));
  },
};
