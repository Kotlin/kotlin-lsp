// Redirects the bare `vscode` specifier to the CJS stub for unit tests (see vscode-stub.cjs).
import { registerHooks } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const STUB_URL = pathToFileURL(fileURLToPath(new URL('./vscode-stub.cjs', import.meta.url))).href;

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === 'vscode') {
      return { url: STUB_URL, shortCircuit: true };
    }
    return nextResolve(specifier, context);
  },
});
