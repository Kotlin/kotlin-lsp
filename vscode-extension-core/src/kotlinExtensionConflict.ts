import type { ExtensionContext } from 'vscode';
import { registerExtensionConflictHandler } from './extensionConflict';

const KOTLIN_SERVER_EXTENSION_ID = 'jetbrains.kotlin-server';
const LEGACY_KOTLIN_EXTENSION_ID = 'jetbrains.kotlin';

export function registerKotlinExtensionConflictHandler(
  context: ExtensionContext,
): Promise<boolean> {
  return registerExtensionConflictHandler({
    context,
    conflictingExtensionIds: [KOTLIN_SERVER_EXTENSION_ID, LEGACY_KOTLIN_EXTENSION_ID],
  });
}
