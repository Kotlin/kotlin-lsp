import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import type * as vscode from 'vscode';
import { changeInvalidatesConflicts } from './showConflictsModel';

interface ChangeEventOptions {
  scheme?: string;
  contentChangeCount?: number;
  /**
   * The numeric value of a `vscode.TextDocumentChangeReason`. The enum is a value of the editor API, which
   * this test cannot load, so the number stands for it.
   */
  reason?: number;
}

/**
 * A `TextDocumentChangeEvent` double which carries the fields the decision reads. The real event holds a
 * whole document and a range for each change, and neither can be built outside the editor.
 */
function changeEvent(options: ChangeEventOptions = {}): vscode.TextDocumentChangeEvent {
  const { scheme = 'file', contentChangeCount = 1, reason } = options;
  return {
    document: { uri: { scheme } },
    contentChanges: Array.from({ length: contentChangeCount }, () => ({})),
    reason,
  } as unknown as vscode.TextDocumentChangeEvent;
}

describe('a change which invalidates the conflicts view', () => {
  it('is an edit of a file', () => {
    assert.equal(changeInvalidatesConflicts(changeEvent()), true);
  });

  it('is an undo as well, because it moves the same offsets', () => {
    assert.equal(changeInvalidatesConflicts(changeEvent({ reason: 1 })), true);
  });

  it('is not an event without a content change, which reports a state of the document', () => {
    assert.equal(changeInvalidatesConflicts(changeEvent({ contentChangeCount: 0 })), false);
  });

  it('is not a write into an output channel, which the log of the server does', () => {
    assert.equal(changeInvalidatesConflicts(changeEvent({ scheme: 'output' })), false);
  });
});
