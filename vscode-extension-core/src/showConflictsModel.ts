import type * as vscode from 'vscode';

/**
 * Whether [event] makes the pending edits of a fix stale, so the conflicts view has to close and cancel the
 * fix.
 *
 * The server computes the edits of a fix before it asks about the conflicts, and it sends them without a
 * document version. An edit which lands while the question is open moves the offsets those edits were built
 * for. The server compares the document versions again after the answer and stops the fix, which tells the
 * user only after a whole confirmation. This closes the view at the moment of the edit instead.
 *
 * An undo and a redo count as an edit. They move the same offsets a typed character moves.
 */
export function changeInvalidatesConflicts(event: vscode.TextDocumentChangeEvent): boolean {
  // The editor also fires this event when only the state of the document changed, such as its dirty flag.
  // Such an event moves no offset.
  if (event.contentChanges.length === 0) return false;
  // An output channel is a text document too, and the log of the server writes into one. Only a file can hold
  // an edit of the fix.
  //
  // An unknown scheme therefore counts as no edit. The server still compares the document versions after the
  // answer, so it stops a fix which this misses. A false alarm has no such backstop: it would close the view
  // for nothing.
  return event.document.uri.scheme === 'file';
}
