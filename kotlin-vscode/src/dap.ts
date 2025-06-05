import {
    CancellationToken,
    commands,
    debug,
    DebugAdapterDescriptorFactory,
    DebugAdapterExecutable,
    DebugAdapterServer,
    DebugConfiguration,
    DebugConfigurationProvider,
    DebugSession,
    type ExtensionContext,
    WorkspaceFolder,
} from "vscode"

export function registerDapServer(context: ExtensionContext) {
    const dapServerFactory: DebugAdapterDescriptorFactory = {
        async createDebugAdapterDescriptor(session: DebugSession, executable: DebugAdapterExecutable) {
            const port: number = await commands.executeCommand("start_debug_server", session.workspaceFolder?.uri.toString());
            return new DebugAdapterServer(port)
        }
    }
    context.subscriptions.push(debug.registerDebugAdapterDescriptorFactory("intellij_debugger", dapServerFactory))

    const debugConfigProvider: DebugConfigurationProvider = {
        async provideDebugConfigurations(folder: WorkspaceFolder, token: CancellationToken) {
            const config: DebugConfiguration = {
                type: "intellij_debugger",
                request: "attach",
                name: "Attach Kotlin Program"
            }
            return [config];
        },

        async resolveDebugConfiguration(folder: WorkspaceFolder, debugConfiguration: DebugConfiguration, token: CancellationToken) {
            return debugConfiguration;
        },

        async resolveDebugConfigurationWithSubstitutedVariables(folder: WorkspaceFolder, debugConfiguration: DebugConfiguration, token: CancellationToken) {
            return debugConfiguration;
        }
    }

    context.subscriptions.push(debug.registerDebugConfigurationProvider("intellij_debugger", debugConfigProvider))
}