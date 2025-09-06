# Helix setup

1. [Install `kotlin-lsp` CLI](../README.md#install-kotlin-lsp-cli)
2. Add the below configuration to your [languages.toml](https://docs.helix-editor.com/languages.html) in `~/.config/helix/languages.toml`:

    ```toml
    [language-server.kotlin]
    command = "kotlin-lsp"
    args = ["--stdio"]
    
    [[language]]
    name = "kotlin"
    language-servers = [ "kotlin" ]
    ```
  
