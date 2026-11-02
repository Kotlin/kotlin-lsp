// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { Location, TextDocumentPositionParams } from 'vscode-languageclient/node';

/** One command for both directions, as the IDE action: the server picks the direction by the caret. */
export const GO_TO_TEST_FROM_EDITOR_COMMAND = 'jetbrains.editor.goToTest';

/**
 * Mirrors the server's `RelatedTestsResult`. `isTest` tells which way the server went: in a test the
 * `targets` are the tested classes, else they are the tests. A missing `sourceName` means there is nothing
 * to start from at the caret, and then `targets` is empty. A whole-file target is an empty range at 0:0.
 */
export interface RelatedTestsResult {
  isTest: boolean;
  targets: Location[];
  sourceName?: string | null;
}

export interface GoToRelatedTestsEditor {
  /** The caret of the active editor; `undefined` without one. */
  activeTarget(): TextDocumentPositionParams | undefined;
  /** `undefined` when the request failed and the editor has reported it. */
  relatedTests(params: TextDocumentPositionParams): Promise<RelatedTestsResult | undefined>;
  /** Opens one location, offers several, and shows `noResultsMessage` at the caret for none. */
  showLocations(options: { locations: Location[]; noResultsMessage: string }): Promise<void>;
}

/** Goes to the tests of the class at the caret, or to the tested classes from a test. */
export async function goToRelatedTests(editor: GoToRelatedTestsEditor): Promise<void> {
  const target = editor.activeTarget();
  if (!target) return;
  const answer = await editor.relatedTests(target);
  // The user may have moved on while the server searched; the answer is for the old caret only.
  if (!answer || !sameTarget(editor.activeTarget(), target)) return;
  await editor.showLocations({
    locations: answer.targets,
    noResultsMessage: noResultsMessage({
      isTest: answer.isTest,
      sourceName: answer.sourceName ?? null,
    }),
  });
}

function sameTarget(
  a: TextDocumentPositionParams | undefined,
  b: TextDocumentPositionParams,
): boolean {
  return (
    a !== undefined &&
    a.textDocument.uri === b.textDocument.uri &&
    a.position.line === b.position.line &&
    a.position.character === b.position.character
  );
}

/** The IDE wording: "No tests found" for a class, "No test subjects found" for a test, with the name when known. */
export function noResultsMessage(options: { isTest: boolean; sourceName: string | null }): string {
  const what = options.isTest ? 'No test subjects found' : 'No tests found';
  return options.sourceName === null ? what : `${what} for ${options.sourceName}`;
}
