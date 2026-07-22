import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  handleCancelledServerDownload,
  handleServerDownloadChecksumMismatch,
} from './serverDownloadRecovery';

describe('server download recovery', () => {
  it('resumes a cancelled download', async () => {
    const calls: string[] = [];

    const result = await handleCancelledServerDownload({
      showInformationMessage: async (message, ...actions) => {
        assert.equal(
          message,
          'Language server download cancelled. Resume now, or run ‘Restart Language Server’ later from the Command Palette.',
        );
        assert.deepEqual(actions, ['Resume Download', 'Delete Downloaded Files']);
        return 'Resume Download';
      },
      resumeDownload: async () => {
        calls.push('resume');
        return '/server/bin/intellij-server';
      },
      deleteDownloadedFiles: async () => {
        calls.push('delete');
      },
    });

    assert.deepEqual(calls, ['resume']);
    assert.equal(result, '/server/bin/intellij-server');
  });

  it('deletes files from a cancelled download', async () => {
    const calls: string[] = [];

    await handleCancelledServerDownload({
      showInformationMessage: async () => 'Delete Downloaded Files',
      resumeDownload: async () => {
        calls.push('resume');
      },
      deleteDownloadedFiles: async () => {
        calls.push('delete');
      },
    });

    assert.deepEqual(calls, ['delete']);
  });

  it('does nothing when the cancellation notification is dismissed', async () => {
    const calls: string[] = [];

    await handleCancelledServerDownload({
      showInformationMessage: async () => undefined,
      resumeDownload: async () => {
        calls.push('resume');
      },
      deleteDownloadedFiles: async () => {
        calls.push('delete');
      },
    });

    assert.deepEqual(calls, []);
  });

  it('redownloads after a checksum mismatch', async () => {
    const result = await handleServerDownloadChecksumMismatch({
      showErrorMessage: async (message, ...actions) => {
        assert.equal(message, 'Language server download failed verification.');
        assert.deepEqual(actions, ['Redownload Server']);
        return 'Redownload Server';
      },
      redownloadServer: async () => '/server/bin/intellij-server',
    });

    assert.equal(result, '/server/bin/intellij-server');
  });

  it('does not redownload when the checksum notification is dismissed', async () => {
    let redownloaded = false;
    const result = await handleServerDownloadChecksumMismatch({
      showErrorMessage: async () => undefined,
      redownloadServer: async () => {
        redownloaded = true;
      },
    });

    assert.equal(redownloaded, false);
    assert.equal(result, undefined);
  });
});
