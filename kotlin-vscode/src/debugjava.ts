import type {ExtensionContext} from "vscode"

/**
 * Base kotlin-vscode build has no debugjava integration.
 */
export function registerDebugJava(_context: ExtensionContext) {
}
