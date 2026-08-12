/**
 * Converts VS Code's HTTP proxy setting to the system properties understood by the JDK proxy selector.
 * Explicit JVM options are appended by the caller, so users can override any generated property.
 */
export function proxyJvmOptions(
  proxy: string | undefined,
  proxySupport: string | undefined,
): string[] {
  if (proxySupport === 'off') return [];
  const value = proxy?.trim();
  if (!value) return [];

  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return [];
  }

  const host = unbracketIpv6Host(url.hostname);
  if (!host) return [];

  if (url.protocol !== 'http:' && url.protocol !== 'https:') return [];
  const port = url.port || (url.protocol === 'https:' ? '443' : '80');
  return [
    `-Dhttp.proxyHost=${host}`,
    `-Dhttp.proxyPort=${port}`,
    `-Dhttps.proxyHost=${host}`,
    `-Dhttps.proxyPort=${port}`,
  ];
}

function unbracketIpv6Host(host: string): string {
  return host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host;
}
