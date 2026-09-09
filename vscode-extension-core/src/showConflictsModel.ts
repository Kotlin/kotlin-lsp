import type * as vscode from 'vscode';

/** A message which ends with one of these carries an end mark already, so it needs no period. */
const TERMINAL_PUNCTUATION_PATTERN = /[.!?;:]$/;

/**
 * The whole message of one conflict on one line.
 *
 * The platform can report several messages for one element, and a row shows them together. Each message is a
 * statement of its own, so it takes a period when it carries no end mark. Without that, two messages read as
 * one run-on line.
 *
 * A message comes from HTML, so it can hold a line break of its own. A row renders no line break, and such a
 * break wraps one statement instead of ending it, so each of them becomes a space and takes no period.
 *
 * The result is empty when no message holds text. The caller decides what to show then.
 */
export function conflictMessage(messages: string[]): string {
  return messages
    .map((message) => oneLine(message))
    .filter((message) => message !== '')
    .map((message) => (TERMINAL_PUNCTUATION_PATTERN.test(message) ? message : `${message}.`))
    .join(' ');
}

/** The lines of [message] as one line, because a row renders no line break. */
function oneLine(message: string): string {
  return message
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
    .join(' ');
}

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
