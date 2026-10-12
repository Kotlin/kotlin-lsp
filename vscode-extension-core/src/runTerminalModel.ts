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
 * the terminal is what the user sees. `externalTerminal` renders in the integrated terminal too: no program runs on
 * the client, so there is no process to put in an OS window, and an invisible stream would be worse than the wrong
 * window.
 */
export function showsStreamInTerminal(console: ConsoleKind | undefined): boolean {
  return console === 'integratedTerminal' || console === 'externalTerminal';
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
