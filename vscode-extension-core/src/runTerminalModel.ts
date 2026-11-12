// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { ConsoleKind } from './dap';

/**
 * The terminal half of a terminal launch, with the editor taken out (see `runTerminal.ts` for the wiring). The
 * server runs every program itself and streams its output as DAP `output` events; nothing runs on the client, so a
 * `console` of `integratedTerminal` means "render that stream in a terminal". These functions decide which sessions
 * get one and what each DAP message writes into it.
 */

/**
 * Whether the output stream of a session with [console] is rendered in a client terminal. The Debug Console shows
 * the same events anyway (a client cannot keep them out of it), but `internalConsoleOptions` keeps it closed, so
 * the terminal is what the user sees. `externalTerminal` renders the stream in an OS window (`externalTerminal.ts`),
 * or in the integrated terminal in a remote window (see [showsStreamInOsWindow]).
 */
export function showsStreamInTerminal(console: ConsoleKind | undefined): boolean {
  return console === 'integratedTerminal' || console === 'externalTerminal';
}

/**
 * Whether a session with [console] shows its stream in an OS window. In a remote window ([remoteName] is set) the
 * extension runs on the remote host, which can have no display, so the integrated terminal shows the stream.
 */
export function showsStreamInOsWindow(
  console: ConsoleKind | undefined,
  remoteName: string | undefined,
): boolean {
  return console === 'externalTerminal' && remoteName === undefined;
}

/** The wire shape of the DAP messages the terminal renders, as a `DebugAdapterTracker` sees them. */
interface DapEventMessage {
  type?: string;
  event?: string;
  body?: { category?: string; output?: string };
}

/**
 * The text [message] contributes to the terminal, or `undefined` for a message that contributes nothing. Every
 * `output` event is rendered except `telemetry`, which is addressed to the client code, not the user (see
 * `ConsoleKind.None` on the server). The end of the program needs nothing of its own: the server reports it in the
 * stream ("Process finished with exit code N", `ProcessTerminatedListener` on its handler), so a client line on the
 * `exited` event would print it twice. Newlines are normalized to CRLF: a pseudoterminal takes a bare `\n` as a
 * line feed without a carriage return.
 */
export function terminalChunk(message: unknown): string | undefined {
  const { type, event, body } = message as DapEventMessage;
  if (type !== 'event' || event !== 'output') return undefined;
  if (body?.output === undefined || body.category === 'telemetry') return undefined;
  return body.output.replace(/\r?\n/g, '\r\n');
}

/** What one input chunk does: the text to echo, the finished lines (with `\n`), and whether Ctrl+C was pressed. */
export interface InputEffect {
  echo: string;
  lines: string[];
  interrupt: boolean;
}

/**
 * The line editing of the integrated terminal. A pseudoterminal gets the raw keys and shows nothing by itself, so
 * the client echoes the typed text, handles Backspace, and sends a line on Enter, like a terminal in cooked mode.
 * Escape sequences (arrow keys and so on) are ignored. A paste can hold `\r`, `\n` or `\r\n` line ends.
 */
export class InputLine {
  private line = '';
  private lastWasCr = false;

  feed(data: string): InputEffect {
    const effect: InputEffect = { echo: '', lines: [], interrupt: false };
    // oxlint-disable-next-line no-control-regex -- the keys of a terminal are control characters
    for (const [key] of data.matchAll(/\x1b(?:\[[0-?]*[ -/]*[@-~]|O.|.)?|[\s\S]/gu)) {
      const wasCr = this.lastWasCr;
      this.lastWasCr = key === '\r';
      if (key === '\r' || (key === '\n' && !wasCr)) {
        effect.lines.push(`${this.line}\n`);
        effect.echo += '\r\n';
        this.line = '';
      } else if (key === '\x7f' || key === '\b') {
        if (this.line === '') continue;
        this.line = Array.from(this.line).slice(0, -1).join('');
        effect.echo += '\b \b';
      } else if (key === '\x03') {
        effect.interrupt = true;
      } else if (key >= ' ' && key !== '\x7f') {
        this.line += key;
        effect.echo += key;
      }
    }
    return effect;
  }
}

/** What a [TerminalInput] does in the terminal and the debug session. */
export interface TerminalInputTarget {
  /** Shows text in the terminal. */
  echo(text: string): void;
  /** Sends a finished line to the program. */
  send(line: string): void;
  /** Stops the session (Ctrl+C). */
  interrupt(): void;
}

/**
 * The input of the integrated terminal of one session: the keys go through an [InputLine] while the session runs.
 * After [end], the keys of the idle terminal go nowhere.
 */
export class TerminalInput {
  private readonly line = new InputLine();
  private ended = false;

  constructor(private readonly target: TerminalInputTarget) {}

  handle(data: string): void {
    if (this.ended) return;
    const { echo, lines, interrupt } = this.line.feed(data);
    if (echo) this.target.echo(echo);
    lines.forEach((line) => this.target.send(line));
    if (interrupt) this.target.interrupt();
  }

  end(): void {
    this.ended = true;
  }
}
