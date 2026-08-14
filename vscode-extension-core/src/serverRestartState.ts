// Copyright 2000-2026 JetBrains s.r.o. and contributors.

export type ServerRestartState = 'restarting' | 'finished' | 'failed';

export function disconnectedServerStartupPhase(
  wasConnected: boolean,
  restarting: boolean,
): 'loading' | 'restarting' | 'lost' {
  if (restarting) return 'restarting';
  return wasConnected ? 'lost' : 'loading';
}
