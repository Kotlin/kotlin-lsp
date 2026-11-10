// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { commands, Uri, window, workspace } from 'vscode';
import type { StackTraceLocation } from './stackTrace';

/**
 * Opens a place of one stack trace frame; its argument is the lines of the trace that end with the frame, which the
 * server resolves then.
 */
export const NAVIGATE_TO_FRAME_COMMAND = 'jetbrains.stackTrace.navigate';

/**
 * Opens the place of a frame. The server may return several places: a class declared in several modules, or a frame
 * whose line no longer matches the code. A choice of them is offered then.
 */
export async function navigateToFrame(locations: readonly StackTraceLocation[]): Promise<void> {
  const location = await chooseLocation(locations);
  if (!location) return;
  const { line, character } = location.range.start;
  await commands.executeCommand('jetbrains.navigateToLocation', location.uri, line, character);
}

async function chooseLocation(
  locations: readonly StackTraceLocation[],
): Promise<StackTraceLocation | undefined> {
  if (locations.length === 0) {
    void window.showInformationMessage('No source found for this frame.');
    return undefined;
  }
  if (locations.length === 1) return locations[0];
  const items = locations.map((location) => {
    const uri = Uri.parse(location.uri);
    return {
      label: `${uri.path.substring(uri.path.lastIndexOf('/') + 1)}:${location.range.start.line + 1}`,
      description: workspace.asRelativePath(uri),
      location,
    };
  });
  return (await window.showQuickPick(items, { placeHolder: 'Choose the place of the frame' }))
    ?.location;
}
