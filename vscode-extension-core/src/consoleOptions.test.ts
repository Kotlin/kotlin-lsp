import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { internalConsoleOptionsFor } from './consoleOptions';

describe('internalConsoleOptionsFor', () => {
  test('opens and focuses the Debug Console on every launch when output goes there', () => {
    assert.equal(internalConsoleOptionsFor('internalConsole'), 'openOnSessionStart');
  });

  test('never opens the Debug Console for the integrated terminal so focus stays on the terminal', () => {
    assert.equal(internalConsoleOptionsFor('integratedTerminal'), 'neverOpen');
  });

  test('never opens the Debug Console for the external terminal so focus does not change', () => {
    assert.equal(internalConsoleOptionsFor('externalTerminal'), 'neverOpen');
  });
});
