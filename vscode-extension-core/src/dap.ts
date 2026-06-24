import {
  commands,
  debug,
  DebugAdapterDescriptorFactory,
  DebugAdapterServer,
  DebugConfiguration,
  DebugConfigurationProvider,
  DebugSession,
  type ExtensionContext,
  WorkspaceFolder,
} from 'vscode';

export function registerDapServer(context: ExtensionContext) {
  const dapServerFactory: DebugAdapterDescriptorFactory = {
    async createDebugAdapterDescriptor(session: DebugSession) {
      const port: number = await commands.executeCommand(
        'start_debug_server',
        session.workspaceFolder?.uri.toString(),
      );
      return new DebugAdapterServer(port);
    },
  };
  context.subscriptions.push(
    debug.registerDebugAdapterDescriptorFactory('intellij_debugger', dapServerFactory),
  );

  const debugConfigProvider: DebugConfigurationProvider = {
    async provideDebugConfigurations() {
      const config: DebugConfiguration = {
        type: 'intellij_debugger',
        request: 'attach',
        name: 'Attach Kotlin Program',
      };
      return [config];
    },

    async resolveDebugConfiguration(
      _folder: WorkspaceFolder,
      debugConfiguration: DebugConfiguration,
    ) {
      return debugConfiguration;
    },

    async resolveDebugConfigurationWithSubstitutedVariables(
      _folder: WorkspaceFolder,
      debugConfiguration: DebugConfiguration,
    ) {
      return debugConfiguration;
    },
  };

  context.subscriptions.push(
    debug.registerDebugConfigurationProvider('intellij_debugger', debugConfigProvider),
  );
}
