// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, test } from 'node:test';
import type { TestItem } from 'vscode';
import {
  GO_TO_TEST_COMMAND,
  GO_TO_TEST_MISSING_CONTEXT,
  type GoToTestEditor,
  registerGoToTest,
} from './goToTestModel';

const FOO_TEST = 'file:///p/src/FooTest.java';
const TESTS_FOLDER = 'file:///p/src/tests';

function editor(uriScheme: string) {
  const contexts = new Map<string, boolean>();
  const commands = new Map<string, (item: TestItem | undefined) => Promise<void>>();
  const opened: string[] = [];
  const fake: GoToTestEditor = {
    uriScheme,
    registerCommand: (command, run) => {
      commands.set(command, run);
      return { dispose: () => commands.delete(command) };
    },
    setContext: (key, value) => contexts.set(key, value),
    isFile: async (uri) => uri.toString() !== TESTS_FOLDER,
    revealInExplorer: async (uri) => {
      opened.push(`Explorer ${uri}`);
    },
    open: async (uri, cursor) => {
      opened.push(cursor ? `${uri}:${cursor.line}:${cursor.character}` : `${uri}`);
    },
  };
  registerGoToTest(fake);
  return {
    buttonShown: () =>
      contexts.get(GO_TO_TEST_MISSING_CONTEXT) === true && commands.has(GO_TO_TEST_COMMAND),
    click: (item: TestItem) => commands.get(GO_TO_TEST_COMMAND)?.(item),
    opened,
  };
}

const testItem = (uri: string, start?: { line: number; character: number }): TestItem =>
  ({ uri: { toString: () => uri }, range: start && { start } }) as unknown as TestItem;

describe('"Go to Test" on a test result', () => {
  test('in Cursor, the button opens the test at the start of its range', async () => {
    const cursor = editor('cursor');

    assert.equal(cursor.buttonShown(), true);
    await cursor.click(testItem(FOO_TEST, { line: 12, character: 2 }));
    assert.deepEqual(cursor.opened, [`${FOO_TEST}:12:2`]);
  });

  test('in VS Code, the editor has its own button, so the extension adds none', () => {
    assert.equal(editor('vscode').buttonShown(), false);
    assert.equal(editor('vscode-insiders').buttonShown(), false);
  });

  test('a test without a range opens at the top of its file', async () => {
    const cursor = editor('cursor');

    await cursor.click(testItem(FOO_TEST));
    assert.deepEqual(cursor.opened, [FOO_TEST]);
  });

  test('a test that is a folder shows the folder in the Explorer', async () => {
    const cursor = editor('cursor');

    await cursor.click(testItem(TESTS_FOLDER));
    assert.deepEqual(cursor.opened, [`Explorer ${TESTS_FOLDER}`]);
  });
});

interface MenuItem {
  command: string;
  when?: string;
  group?: string;
}

interface Manifest {
  contributes: { menus?: Record<string, MenuItem[] | undefined> };
}

const readManifest = (path: string): Manifest =>
  JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8')) as Manifest;

const TEST_PRODUCTS = [
  '../../kotlin-vscode/package.json',
  '../../../intellij-vscode/intellij-server/package.json',
  '../../../intellij-vscode/intellij-server-experimental/package.json',
];
/** `check-metadata-sync.mjs` copies the command into these products too. */
const OTHER_PRODUCTS = [
  '../../../intellij-vscode/goland-server/package.json',
  '../../../intellij-vscode/datagrip-server/package.json',
];

describe('"Go to Test" in the product manifests', () => {
  test('a test product shows the button on a test result while the editor lacks one', () => {
    for (const path of TEST_PRODUCTS) {
      const item = readManifest(path).contributes.menus?.['testing/item/result']?.find(
        (entry) => entry.command === GO_TO_TEST_COMMAND,
      );
      assert.equal(item?.group, 'inline', path);
      assert.ok(item.when?.split(' && ').includes(GO_TO_TEST_MISSING_CONTEXT), path);
    }
  });

  test('no product offers the command in the Command Palette', () => {
    for (const path of [...TEST_PRODUCTS, ...OTHER_PRODUCTS]) {
      const item = readManifest(path).contributes.menus?.commandPalette?.find(
        (entry) => entry.command === GO_TO_TEST_COMMAND,
      );
      assert.equal(item?.when, 'false', path);
    }
  });
});
