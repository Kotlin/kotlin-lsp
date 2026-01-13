import {Middleware} from 'vscode-languageclient/node';

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

                        delete (part as any).location;
                        (part as any).command = {
                            title: 'Go to definition',
                            command: 'jetbrains.navigateToJarLocation',
                            arguments: [uri.toString(), range.start.line, range.start.character]
                        };
                        (part as any).tooltip = (part as any).command.title;
                    }
                }
            }
        }

        return result;
    }
};
