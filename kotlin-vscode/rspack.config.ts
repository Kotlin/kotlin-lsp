import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from '@rspack/cli';
import { copyToPackage, createExtensionConfig, dependencyWasm } from './rspack.base.config';
import { configureExtensionPolicyConsumer } from '../../intellij-vscode/extension-policy/rspack.consumer';

const packageDir = path.dirname(fileURLToPath(import.meta.url));
const communityDir = path.resolve(packageDir, '..');
const intellijVscodeDir = path.resolve(communityDir, '../intellij-vscode');
const policyDir = path.join(intellijVscodeDir, 'extension-policy');

const config = createExtensionConfig(
  packageDir,
  [
    dependencyWasm(packageDir, 'web-tree-sitter', 'web-tree-sitter.wasm'),
    copyToPackage(
      packageDir,
      path.resolve(communityDir, 'vscode-language-kotlin/grammars/tree-sitter-kotlin.wasm'),
      'grammars/tree-sitter-kotlin.wasm',
    ),
  ],
  './src/extension.ts',
  [
    path.resolve(packageDir, 'src'),
    path.resolve(communityDir, 'vscode-extension-core/src'),
    path.resolve(communityDir, 'vscode-language-kotlin/src'),
    path.resolve(policyDir, 'src'),
  ],
);
configureExtensionPolicyConsumer({
  config,
  policyDir,
  buildCwd: intellijVscodeDir,
  eulaGate: false,
  statusIconFont: path.join(intellijVscodeDir, 'icons/lsp-ij.woff'),
});

export default defineConfig(config);
