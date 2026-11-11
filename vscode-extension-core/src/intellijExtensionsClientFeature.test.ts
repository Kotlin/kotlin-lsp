import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { ClientCapabilities } from 'vscode-languageclient/node';
import { createIntellijExtensionsClientFeature } from './intellijExtensionsClientFeature';

test('declares extension support in experimental client capabilities', () => {
  const capabilities: ClientCapabilities = {};
  createIntellijExtensionsClientFeature().fillClientCapabilities(capabilities);
  assert.deepEqual(capabilities.experimental, {
    conflictView: true,
    actionChooser: true,
    copyToClipboard: true,
    runEditorCommand: true,
  });
});

test('keeps experimental capabilities from other features', () => {
  const capabilities: ClientCapabilities = {
    experimental: { licensingUi: true, futureCapability: {} },
  };
  createIntellijExtensionsClientFeature().fillClientCapabilities(capabilities);
  assert.deepEqual(capabilities.experimental, {
    licensingUi: true,
    futureCapability: {},
    conflictView: true,
    actionChooser: true,
    copyToClipboard: true,
    runEditorCommand: true,
  });
});
