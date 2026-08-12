import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  consumeJbaSignInPageCopied,
  copiedExternalHttpUrl,
  isJbaSignInUrl,
  recordJbaSignInPageCopied,
} from './showDocumentResult';

const loginUrl = 'https://account.jetbrains.com/oauth/login?state=abc';

test('recognizes a copied JBA sign-in URL', () => {
  assert.equal(copiedExternalHttpUrl(loginUrl, loginUrl), true);
  assert.equal(
    copiedExternalHttpUrl(
      'https://account.jetbrains.com/oauth/login?scope=openid%20offline_access',
      'https://account.jetbrains.com/oauth/login?scope=openid offline_access',
    ),
    true,
  );
});

test('does not recognize an unchanged clipboard as Copy', () => {
  assert.equal(copiedExternalHttpUrl(loginUrl, 'previous clipboard value'), false);
});

test('recognizes a copied FLS authorization URL without requiring the JBA host', () => {
  const flsUrl = 'https://license.example.com/oauth/authorize?state=abc';
  assert.equal(copiedExternalHttpUrl(flsUrl, flsUrl), true);
  assert.equal(copiedExternalHttpUrl(flsUrl, 'previous clipboard value'), false);
  assert.equal(copiedExternalHttpUrl('file:///tmp/auth', 'file:///tmp/auth'), false);
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
