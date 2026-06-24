import * as vscode from 'vscode';
import { Middleware } from 'vscode-languageclient/node';

const NAVIGATE_COMMAND = 'jetbrains.navigateToLocation';

export const middleware: Middleware = {
  resolveInlayHint: async (hint, token, next) => {
    /*
     * Replaces location with command for jar/jrt schemes when resolving an inlay hint.
     * See LSP-393
     */
    const result = await next(hint, token);

    if (result && result.label && typeof result.label === 'object' && Array.isArray(result.label)) {
      for (const part of result.label) {
        if ('location' in part && part.location) {
          const uri = part.location.uri;

          if (uri.scheme === 'jar' || uri.scheme === 'jrt') {
            const range = part.location.range;

            delete part.location;
            part.command = {
              title: 'Go to definition',
              command: NAVIGATE_COMMAND,
              arguments: [uri.toString(), range.start.line, range.start.character],
            };
            part.tooltip = part.command.title;
          }
        }
      }
    }

    return result;
  },

  provideHover: async (document, position, token, next) => {
    /*
     * The server embeds navigation links (e.g. "Go to Super Method") in hover markdown as
     * `command:` links that run `jetbrains.navigateToLocation`. VS Code only renders command
     * links when the markdown is trusted, and that trust flag does not exist in the LSP
     * protocol, so it has to be set here on the client. We scope the trust to this single
     * command rather than trusting all commands.
     */
    const hover = await next(document, position, token);
    if (!hover) return hover;
    for (const content of hover.contents) {
      if (content instanceof vscode.MarkdownString) {
        content.isTrusted = { enabledCommands: [NAVIGATE_COMMAND] };
      }
    }
    return hover;
  },
};
