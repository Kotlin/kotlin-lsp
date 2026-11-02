// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { Location, TextDocumentPositionParams } from 'vscode-languageclient/node';
import {
  goToRelatedTests,
  type GoToRelatedTestsEditor,
  noResultsMessage,
  type RelatedTestsResult,
} from './relatedTestsModel';

const CART = 'file:///p/src/shop/Cart.java';
const CART_TEST = 'file:///p/testSrc/shop/CartTest.java';
const ORDER = 'file:///p/src/shop/Order.kt';

const at = (uri: string, line: number): Location => ({
  uri,
  range: { start: { line, character: 13 }, end: { line, character: 17 } },
});
const wholeFile = (uri: string): Location => ({
  uri,
  range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
});
const caret = (uri: string, line = 2): TextDocumentPositionParams => ({
  textDocument: { uri },
  position: { line, character: 14 },
});

interface Shown {
  locations: Location[];
  noResultsMessage: string;
}

function editor(options: {
  active?: TextDocumentPositionParams;
  answer: RelatedTestsResult | undefined;
  /** Where the user is once the server has answered; the same place unless set. */
  activeAfterAnswer?: TextDocumentPositionParams;
}) {
  let active = options.active;
  const requests: TextDocumentPositionParams[] = [];
  const shown: Shown[] = [];
  const fake: GoToRelatedTestsEditor = {
    activeTarget: () => active,
    relatedTests: (params) => {
      requests.push(params);
      if (options.activeAfterAnswer) active = options.activeAfterAnswer;
      return Promise.resolve(options.answer);
    },
    showLocations: async (request) => {
      shown.push(request);
    },
  };
  return { goToTest: () => goToRelatedTests(fake), requests, shown };
}

const answer = (
  isTest: boolean,
  targets: Location[],
  sourceName?: string | null,
): RelatedTestsResult => ({ isTest, targets, sourceName });

describe('Go to Test from the editor', () => {
  test('asks the server for the caret of the active editor', async () => {
    const e = editor({ active: caret(CART), answer: answer(false, [at(CART_TEST, 4)], 'Cart') });

    await e.goToTest();

    assert.deepEqual(e.requests, [caret(CART)]);
  });

  test('a class with several tests shows all of them', async () => {
    const targets = [at(CART_TEST, 4), at('file:///p/testSrc/shop/CartIntegrationTest.java', 4)];
    const e = editor({ active: caret(CART), answer: answer(false, targets, 'Cart') });

    await e.goToTest();

    assert.deepEqual(e.shown, [
      { locations: targets, noResultsMessage: 'No tests found for Cart' },
    ]);
  });

  test('a class without tests shows the not-found message with its name', async () => {
    const e = editor({
      active: caret('file:///p/src/shop/Wallet.java'),
      answer: answer(false, [], 'Wallet'),
    });

    await e.goToTest();

    assert.deepEqual(e.shown, [{ locations: [], noResultsMessage: 'No tests found for Wallet' }]);
  });

  test('a caret with nothing to start from shows the message without a name', async () => {
    const e = editor({ active: caret(CART), answer: answer(false, [], null) });

    await e.goToTest();

    assert.deepEqual(e.shown, [{ locations: [], noResultsMessage: 'No tests found' }]);
  });

  test('a server that omits the name is read as no name', async () => {
    const e = editor({ active: caret(CART_TEST), answer: answer(true, []) });

    await e.goToTest();

    assert.deepEqual(e.shown, [{ locations: [], noResultsMessage: 'No test subjects found' }]);
  });

  test('in a test the command goes to the tested class', async () => {
    const e = editor({ active: caret(CART_TEST), answer: answer(true, [at(CART, 2)], 'CartTest') });

    await e.goToTest();

    assert.deepEqual(e.shown, [
      { locations: [at(CART, 2)], noResultsMessage: 'No test subjects found for CartTest' },
    ]);
  });

  test('a Kotlin test goes to the file and the class of its subject', async () => {
    const targets = [wholeFile(ORDER), at(ORDER, 2)];
    const e = editor({
      active: caret('file:///p/testSrc/shop/OrderTest.kt'),
      answer: answer(true, targets, 'OrderTest'),
    });

    await e.goToTest();

    assert.deepEqual(e.shown, [
      { locations: targets, noResultsMessage: 'No test subjects found for OrderTest' },
    ]);
  });

  test('without an active editor nothing is asked', async () => {
    const e = editor({ answer: answer(false, [at(CART_TEST, 4)], 'Cart') });

    await e.goToTest();

    assert.deepEqual(e.requests, []);
    assert.deepEqual(e.shown, []);
  });

  test('an answer for a document the user has left is dropped', async () => {
    const e = editor({
      active: caret(CART),
      answer: answer(false, [at(CART_TEST, 4)], 'Cart'),
      activeAfterAnswer: caret(ORDER),
    });

    await e.goToTest();

    assert.deepEqual(e.shown, []);
  });

  test('an answer for a caret the user has moved is dropped', async () => {
    const e = editor({
      active: caret(CART, 2),
      answer: answer(false, [at(CART_TEST, 4)], 'Cart'),
      activeAfterAnswer: caret(CART, 5),
    });

    await e.goToTest();

    assert.deepEqual(e.shown, []);
  });

  test('a request without an answer shows nothing', async () => {
    const e = editor({ active: caret(CART), answer: undefined });

    await e.goToTest();

    assert.deepEqual(e.shown, []);
  });

  test('the not-found message names the side the server searched and the source', () => {
    assert.equal(
      noResultsMessage({ isTest: false, sourceName: 'Cart' }),
      'No tests found for Cart',
    );
    assert.equal(noResultsMessage({ isTest: true, sourceName: null }), 'No test subjects found');
  });
});
