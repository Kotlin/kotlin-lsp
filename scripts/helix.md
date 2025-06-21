# helix setup

## stdio way

1. Download the standalone zip from the [Releases Page](https://github.com/Kotlin/kotlin-lsp/releases)

2. Ensure `kotlin-lsp.sh` is executable
   
    ```sh
    chmod +x $KOTLIN_LSP_DIR/kotlin-lsp.sh
    ```

3. Create a symlink inside your `PATH` to `kotlin-lsp.sh` script, e.g.:

    ```sh
    ln -s $KOTLIN_LSP_DIR/kotlin-lsp.sh $HOME/.local/bin/kotlin-ls
    ```

4. Add the below configuration to your [languages.toml](https://docs.helix-editor.com/languages.html) in `~/.config/helix/languages.toml`:

    ```toml
    [language-server.kotlin]
    command = "kotlin-ls"
    args = ["--stdio"]
    
    [[language]]
    name = "kotlin"
    language-servers = [ "kotlin" ]
    ```
  
