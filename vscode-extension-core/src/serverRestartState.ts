// Copyright 2000-2026 JetBrains s.r.o. and contributors.

export type ServerRestartState = 'restarting' | 'finished' | 'failed';

export type DisconnectedServerStartupPhase = 'loading' | 'restarting' | 'lost';

export interface DisconnectedServerStartupOptions {
  wasConnected: boolean;
  restarting: boolean;
}

export function disconnectedServerStartupPhase({
  wasConnected,
  restarting,
}: DisconnectedServerStartupOptions): DisconnectedServerStartupPhase {
  if (restarting) return 'restarting';
  return wasConnected ? 'lost' : 'loading';
}
