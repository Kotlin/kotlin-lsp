import { workspace } from "vscode";

export function runWithJavaSupport(): boolean {
    return workspace.getConfiguration().get<boolean>('kotlinLSP.javaSupport') || false
}
