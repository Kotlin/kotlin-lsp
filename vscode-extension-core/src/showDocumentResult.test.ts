import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  consumeJbaSignInPageCopied,
  copiedJbaSignInUrl,
  isJbaSignInUrl,
  recordJbaSignInPageCopied,
} from './showDocumentResult';

const loginUrl = 'https://account.jetbrains.com/oauth/login?state=abc';

test('recognizes Copy from a declined external JBA showDocument request', () => {
  assert.equal(copiedJbaSignInUrl(loginUrl, true, false, loginUrl), true);
  assert.equal(
    copiedJbaSignInUrl(
      'https://account.jetbrains.com/oauth/login?scope=openid%20offline_access',
      true,
      false,
      'https://account.jetbrains.com/oauth/login?scope=openid offline_access',
    ),
    true,
  );
});

test('does not reinterpret Cancel or unrelated showDocument requests', () => {
  assert.equal(copiedJbaSignInUrl(loginUrl, true, false, 'previous clipboard value'), false);
  assert.equal(copiedJbaSignInUrl(loginUrl, false, false, loginUrl), false);
  assert.equal(copiedJbaSignInUrl(loginUrl, true, true, loginUrl), false);
  assert.equal(
    copiedJbaSignInUrl('https://example.com/login', true, false, 'https://example.com/login'),
    false,
  );
  assert.equal(
    copiedJbaSignInUrl(
      'http://account.jetbrains.com/oauth/login',
      true,
      false,
      'http://account.jetbrains.com/oauth/login',
    ),
    false,
  );
});

test('recognizes only HTTPS JetBrains Account sign-in URLs', () => {
  assert.equal(isJbaSignInUrl(loginUrl), true);
  assert.equal(isJbaSignInUrl('http://account.jetbrains.com/oauth/login'), false);
  assert.equal(isJbaSignInUrl('https://example.com/login'), false);
});

test('the copied-page marker is consumed once', () => {
  consumeJbaSignInPageCopied();
  recordJbaSignInPageCopied();
  assert.equal(consumeJbaSignInPageCopied(), true);
  assert.equal(consumeJbaSignInPageCopied(), false);
});
