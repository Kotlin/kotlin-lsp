import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from '@rspack/cli';
import { createExtensionConfig, dependencyWasm } from './rspack.base.config';

const packageDir = path.dirname(fileURLToPath(import.meta.url));
const communityDir = path.resolve(packageDir, '..');

export default defineConfig(
  createExtensionConfig(
    packageDir,
    [
      dependencyWasm(packageDir, 'web-tree-sitter', 'web-tree-sitter.wasm'),
      dependencyWasm(
        packageDir,
        '@tree-sitter-grammars/tree-sitter-kotlin',
        'tree-sitter-kotlin.wasm',
      ),
    ],
    './src/extension.ts',
    [
      path.resolve(packageDir, 'src'),
      path.resolve(communityDir, 'vscode-extension-core/src'),
      path.resolve(communityDir, 'vscode-language-kotlin/src'),
    ],
  ),
);
