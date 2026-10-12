// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { ConsoleKind } from './dap';

export type InternalConsoleOptions = 'neverOpen' | 'openOnSessionStart' | 'openOnFirstSessionStart';

/**
 * Derives VSCode's `internalConsoleOptions` from where the program runs, so focus follows the
 * chosen `console` rather than always landing on the Debug Console:
 *
 *  - `internalConsole`    → `openOnSessionStart`: program output is streamed to the Debug Console,
 *                           so open and focus it on every launch.
 *  - `integratedTerminal` → `neverOpen`: the streamed output is rendered in an integrated terminal
 *                           (`runTerminal.ts`), so keep the Debug Console closed — it would show a
 *                           copy of the same events on top of the terminal the user launched into.
 *  - `externalTerminal`   → `neverOpen`: rendered in the integrated terminal too (the server runs
 *                           the program, so there is no process to put in an OS window), so the
 *                           Debug Console stays closed the same way.
 *  - `none`               → `neverOpen`: the adapter reports the output as `telemetry`, so the Debug
 *                           Console stays empty and there is nothing to open it for.
 *
 * Without this, VSCode's default (`openOnFirstSessionStart`) pops the Debug Console on session start
 * even for terminal launches, stealing focus back after the terminal was focused.
 */
export function internalConsoleOptionsFor(console: ConsoleKind): InternalConsoleOptions {
  return console === 'internalConsole' ? 'openOnSessionStart' : 'neverOpen';
}
