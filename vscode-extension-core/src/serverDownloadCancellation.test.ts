import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { handleCancelledServerDownload } from './serverDownloadCancellation';

describe('cancelled server download', () => {
  it('resumes the download', async () => {
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

  it('deletes downloaded files', async () => {
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

  it('does nothing when the notification is dismissed', async () => {
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
});
