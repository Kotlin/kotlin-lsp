const JBA_ACCOUNT_HOST = 'account.jetbrains.com';
// The core middleware writes this marker and extension-policy consumes it from the same rspack
// compilation; rspack.consumer.ts aliases policy to source so both imports share this module.
let jbaSignInPageCopied = false;

export function recordJbaSignInPageCopied(): void {
  jbaSignInPageCopied = true;
}

export function consumeJbaSignInPageCopied(): boolean {
  const copied = jbaSignInPageCopied;
  jbaSignInPageCopied = false;
  return copied;
}

export function isJbaSignInUrl(uri: string): boolean {
  try {
    const parsed = new URL(uri);
    return parsed.protocol === 'https:' && parsed.hostname === JBA_ACCOUNT_HOST;
  } catch {
    return false;
  }
}

export function copiedExternalHttpUrl(uri: string, clipboardText: string): boolean {
  try {
    const requested = new URL(uri);
    const copied = new URL(clipboardText);
    return (
      (requested.protocol === 'https:' || requested.protocol === 'http:') &&
      copied.href === requested.href
    );
  } catch {
    return false;
  }
}
