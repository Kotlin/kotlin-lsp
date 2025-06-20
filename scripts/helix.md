# Helix setup

1. [Install `kotlin-lsp` CLI](../README.md#install-kotlin-lsp-cli)
2. Make sure that the `kotlin-lsp` binary is on your `$PATH`
3. Add the below configuration to your [languages.toml](https://docs.helix-editor.com/languages.html) in `~/.config/helix/languages.toml`:

    ```toml
    [[language]]
    name = "kotlin"
    language-servers = [ "kotlin-lsp" ]
    ```
