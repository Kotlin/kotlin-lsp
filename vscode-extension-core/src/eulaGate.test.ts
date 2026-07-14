import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { runWithEulaGate } from './eulaGate';

describe('runWithEulaGate', () => {
  it('runs the action after EULA acceptance', async () => {
    let actionCalled = false;

    const accepted = await runWithEulaGate({
      checkEulaAccepted: async () => true,
      action: async () => {
        actionCalled = true;
      },
    });

    assert.equal(accepted, true);
    assert.equal(actionCalled, true);
  });

  it('does not run the action when EULA acceptance is incomplete', async () => {
    let actionCalled = false;

    const accepted = await runWithEulaGate({
      checkEulaAccepted: async () => false,
      action: async () => {
        actionCalled = true;
      },
    });

    assert.equal(accepted, false);
    assert.equal(actionCalled, false);
  });
});
