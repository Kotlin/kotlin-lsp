import { NotificationType, RequestType } from 'vscode-languageclient/node';

/** Mirrors the server's `WorkspaceImportPhase`. */
export type WorkspaceImportPhase = 'IN_PROGRESS' | 'FINISHED' | 'CANCELLED' | 'FAILED';

/** Mirrors the server's `WorkspaceImportStateParams`; only the phase is used here. */
export interface WorkspaceImportState {
  phase: WorkspaceImportPhase;
}

/** Sent once per import cycle, when the cycle ends — so never with `IN_PROGRESS`. */
export const WorkspaceImportStateNotification = new NotificationType<WorkspaceImportState>(
  'intellij/workspaceImportState',
);

/**
 * Answers `IN_PROGRESS` while a cycle is running, and the last cycle's phase otherwise. A restarted
 * server answers `FINISHED` until its own import starts.
 *
 * The parameters are an empty object, not none: the server reads them as `Unit`, which needs a `{}`
 * on the wire.
 */
export const WorkspaceImportStateRequest = new RequestType<
  Record<string, never>,
  WorkspaceImportState,
  void
>('intellij/workspaceImportState');
