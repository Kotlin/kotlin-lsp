/**
 * WSL forwards only IPv4 localhost listeners to Windows (wslrelay.exe), while the JDK opens
 * dual-stack sockets by default. The JBA sign-in callback on http://localhost:<port> is then
 * unreachable from the Windows browser (ERR_CONNECTION_REFUSED). Force the IPv4 stack for the
 * server process under WSL, as the remote-dev launcher does. Explicit JVM options are appended by
 * the caller, so `-Djava.net.preferIPv4Stack=false` in `intellij.additionalJvmArgs` restores IPv6.
 */
export function wslJvmOptions(
  platform: NodeJS.Platform,
  remoteName: string | undefined,
  env: NodeJS.ProcessEnv,
): string[] {
  if (platform !== 'linux') return [];
  const underWsl = remoteName === 'wsl' || !!env.WSL_DISTRO_NAME || !!env.WSL_INTEROP;
  return underWsl ? ['-Djava.net.preferIPv4Stack=true'] : [];
}
