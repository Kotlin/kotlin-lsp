// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type {ExtensionContext} from 'vscode';

/**
 * Stub: the community Kotlin LSP build does not require EULA acceptance.
 */
export async function checkEulaAccepted(_context: ExtensionContext): Promise<boolean> {
    return true;
}

export function getAcceptedEulaHash(_context: ExtensionContext): string | undefined {
    return undefined;
}
