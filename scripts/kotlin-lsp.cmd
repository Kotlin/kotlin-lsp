@echo off

echo WARNING: kotlin-lsp.cmd is deprecated and will be removed in a future release. Use bin\languageServer.exe instead. 1>&2

set "DIR=%~dp0"
"%DIR%bin\languageServer.exe" %*
