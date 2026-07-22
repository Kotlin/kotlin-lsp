const RESUME_DOWNLOAD = 'Resume Download';
const DELETE_DOWNLOADED_FILES = 'Delete Downloaded Files';

interface CancelledServerDownloadOptions<T> {
  showInformationMessage: (
    message: string,
    ...actions: string[]
  ) => PromiseLike<string | undefined>;
  resumeDownload: () => Promise<T>;
  deleteDownloadedFiles: () => Promise<void>;
}

export async function handleCancelledServerDownload<T>({
  showInformationMessage,
  resumeDownload,
  deleteDownloadedFiles,
}: CancelledServerDownloadOptions<T>): Promise<T | undefined> {
  const action = await showInformationMessage(
    'Language server download cancelled. Resume now, or run ‘Restart Language Server’ later from the Command Palette.',
    RESUME_DOWNLOAD,
    DELETE_DOWNLOADED_FILES,
  );
  if (action === RESUME_DOWNLOAD) {
    return resumeDownload();
  } else if (action === DELETE_DOWNLOADED_FILES) {
    await deleteDownloadedFiles();
  }
  return undefined;
}
