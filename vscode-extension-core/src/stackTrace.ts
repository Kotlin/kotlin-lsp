// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { window } from 'vscode';
import type { LanguageClient } from 'vscode-languageclient/node';
import { getLspClient, sendLspCommand } from './lspClient';
import { navigateToFrame } from './stackTraceNavigation';

const PATTERNS_COMMAND = 'jetbrains.stackTrace.patterns';
const RESOLVE_COMMAND = 'jetbrains.stackTrace.resolve';

/** The wire shape of an LSP `Location` the server returns. */
export interface StackTraceLocation {
  readonly uri: string;
  readonly range: { readonly start: { readonly line: number; readonly character: number } };
}

/** The lines of the stack traces of the server's languages. */
export interface StackTracePatterns {
  /** The frames, the lines linked to the code. */
  readonly frames: readonly RegExp[];
  /** The other lines inside a trace: a cause, elided frames. */
  readonly traceLines: readonly RegExp[];
}

const NO_PATTERNS: StackTracePatterns = { frames: [], traceLines: [] };

// Of the current client only: a restarted server may come with other languages.
let cached:
  | { readonly client: LanguageClient; readonly patterns: Promise<StackTracePatterns> }
  | undefined;

/** The patterns of the server, none when it resolves no stack traces. A failed request is asked again next time. */
export function stackTracePatterns(): Promise<StackTracePatterns> {
  const client = getLspClient();
  if (!client || !serverHas(client, PATTERNS_COMMAND)) return Promise.resolve(NO_PATTERNS);
  if (cached?.client === client) return cached.patterns;
  const patterns = sendLspCommand<{ framePatterns: string[]; traceLinePatterns: string[] }>(
    client,
    PATTERNS_COMMAND,
    [],
  ).then(
    ({ framePatterns, traceLinePatterns }) => ({
      frames: regExps(framePatterns),
      traceLines: regExps(traceLinePatterns),
    }),
    () => {
      if (cached?.patterns === patterns) cached = undefined;
      return NO_PATTERNS;
    },
  );
  cached = { client, patterns };
  return patterns;
}

/** Whether the server resolves stack traces at all; it has no patterns when none of its languages does. */
export function serverResolvesStackTraces(): boolean {
  const client = getLspClient();
  return client !== undefined && serverHas(client, RESOLVE_COMMAND);
}

/**
 * The places each of [lines] points to: empty for a line that points to no code, several for a frame of a class
 * declared in several modules or a frame whose line no longer matches the code.
 */
export async function resolveStackTrace(lines: readonly string[]): Promise<StackTraceLocation[][]> {
  const client = getLspClient();
  if (!client) throw new Error('IntelliJ LSP is not running');
  return await sendLspCommand<StackTraceLocation[][]>(client, RESOLVE_COMMAND, [{ lines }]);
}

/** Opens the place of the frame that ends [lines], resolved by the server now: the code may have changed since. */
export async function navigateToTrace(lines: readonly string[]): Promise<void> {
  try {
    const locationsByLine = await resolveStackTrace(lines);
    await navigateToFrame(locationsByLine.at(-1) ?? []);
  } catch (e) {
    void window.showErrorMessage(`Failed to resolve the stack trace: ${e}`);
  }
}

function serverHas(client: LanguageClient, command: string): boolean {
  return (
    client.initializeResult?.capabilities.executeCommandProvider?.commands.includes(command) ??
    false
  );
}

function regExps(sources: readonly string[]): RegExp[] {
  return sources.flatMap((source) => {
    try {
      return [new RegExp(source)];
    } catch (e) {
      console.error(`[stackTrace] The server sent a pattern JavaScript cannot run: ${source}`, e);
      return [];
    }
  });
}
