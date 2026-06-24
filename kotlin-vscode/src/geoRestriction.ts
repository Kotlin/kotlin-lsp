// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { type Extension, window } from 'vscode';
import { logInfo } from '@jetbrains/vscode-extension-core';

const GEO_CHECK_URL = 'https://download.jetbrains.com/check-location';
const UNAVAILABLE_FOR_LEGAL_REASONS_STATUS = 451;
const FALLBACK_MESSAGE =
  'We are sorry, but we are currently unable to provide our products or services to you due to export control regulations';

/**
 * Side effect: when geo-restriction is applied, this shows the error notification.
 * Callers must not show their own message.
 */
export async function checkGeoRestricted(extension: Extension<unknown>): Promise<boolean> {
  try {
    const result = await fetch(GEO_CHECK_URL, {
      headers: {
        'User-Agent': `${extension.id}, version: ${extension.packageJSON.version}`,
      },
    });

    if (result.status === UNAVAILABLE_FOR_LEGAL_REASONS_STATUS) {
      logInfo(`Geo-restriction check returned status ${result.status}`);

      const errorMessage = result.headers.get('error-message') ?? FALLBACK_MESSAGE;
      void window.showErrorMessage(`Unavailable For Legal Reasons: ${errorMessage}`);

      return true;
    }
  } catch (e) {
    logInfo(`Geo-restriction check failed with error: ${e}`);
  }

  return false;
}
