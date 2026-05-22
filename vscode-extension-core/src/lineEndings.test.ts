import test from 'node:test';
import assert from 'node:assert/strict';
import { toCrLfLineEndings } from './lineEndings';

test('converts all LF line endings to CRLF', () => {
    assert.strictEqual(toCrLfLineEndings('a\nb\nc'), 'a\r\nb\r\nc');
});

test('keeps existing CRLF line endings unchanged', () => {
    assert.strictEqual(toCrLfLineEndings('a\r\nb\r\nc'), 'a\r\nb\r\nc');
});
