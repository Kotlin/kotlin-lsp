const RESUME_DOWNLOAD = 'Resume Download';
const RESUME_SETUP = 'Resume Setup';
const DELETE_DOWNLOADED_FILES = 'Delete Downloaded Files';
const REDOWNLOAD_SERVER = 'Redownload Server';
const CANCELLATION_PHASE_COPY: Record<ServerBundlePhase, { label: string; resumeAction: string }> =
  {
    waiting: { label: 'setup', resumeAction: RESUME_SETUP },
    downloading: { label: 'download', resumeAction: RESUME_DOWNLOAD },
    verifying: { label: 'verification', resumeAction: RESUME_SETUP },
    extracting: { label: 'extraction', resumeAction: RESUME_SETUP },
    installing: { label: 'installation', resumeAction: RESUME_SETUP },
  };

interface CancelledServerDownloadOptions<T> {
  phase: ServerBundlePhase;
  showInformationMessage: (
    message: string,
    ...actions: string[]
  ) => PromiseLike<string | undefined>;
  resumeDownload: () => Promise<T>;
  deleteDownloadedFiles: () => Promise<void>;
}

export async function handleCancelledServerDownload<T>({
  phase,
  showInformationMessage,
  resumeDownload,
  deleteDownloadedFiles,
}: CancelledServerDownloadOptions<T>): Promise<T | undefined> {
  const { label, resumeAction } = CANCELLATION_PHASE_COPY[phase];
  const action = await showInformationMessage(
    `Language server ${label} cancelled. Resume now, or run ‘Restart Language Server’ later from the Command Palette.`,
    resumeAction,
    DELETE_DOWNLOADED_FILES,
  );
  if (action === resumeAction) {
    return resumeDownload();
  } else if (action === DELETE_DOWNLOADED_FILES) {
    await deleteDownloadedFiles();
  }
  return undefined;
}

interface ServerDownloadChecksumMismatchOptions<T> {
  showErrorMessage: (message: string, ...actions: string[]) => PromiseLike<string | undefined>;
  redownloadServer: () => Promise<T>;
}

export async function handleServerDownloadChecksumMismatch<T>({
  showErrorMessage,
  redownloadServer,
}: ServerDownloadChecksumMismatchOptions<T>): Promise<T | undefined> {
  const action = await showErrorMessage(
    'Language server download failed verification.',
    REDOWNLOAD_SERVER,
  );
  return action === REDOWNLOAD_SERVER ? redownloadServer() : undefined;
}
import type { ServerBundlePhase } from './serverBundleDownload';
