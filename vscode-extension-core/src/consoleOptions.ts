// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { ConsoleKind } from './dap';

export type InternalConsoleOptions = 'neverOpen' | 'openOnSessionStart' | 'openOnFirstSessionStart';

/**
 * Derives VSCode's `internalConsoleOptions` from where the program runs, so focus follows the
 * chosen `console` rather than always landing on the Debug Console:
 *
 *  - `internalConsole`    → `openOnSessionStart`: program output is streamed to the Debug Console,
 *                           so open and focus it on every launch.
 *  - `integratedTerminal` → `neverOpen`: leave the Debug Console closed so it does not steal focus
 *                           from the integrated terminal VSCode focuses for the program.
 *  - `externalTerminal`   → `neverOpen`: the program runs in a separate OS window, so keep the
 *                           Debug Console closed and leave focus where it was.
 *  - `none`               → `neverOpen`: the adapter reports the output as `telemetry`, so the Debug
 *                           Console stays empty and there is nothing to open it for.
 *
 * Without this, VSCode's default (`openOnFirstSessionStart`) pops the Debug Console on session start
 * even for terminal launches, stealing focus back after the terminal was focused.
 */
export function internalConsoleOptionsFor(console: ConsoleKind): InternalConsoleOptions {
  return console === 'internalConsole' ? 'openOnSessionStart' : 'neverOpen';
}
