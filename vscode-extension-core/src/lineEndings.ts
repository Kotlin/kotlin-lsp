export function toCrLfLineEndings(text: string): string {
  return text.replace(/\r?\n/g, '\r\n');
}
