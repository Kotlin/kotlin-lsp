# helix setup

## stdio way

1. Ensure `kotlin-lsp.sh` is executable
   
    ```sh
    chmod +x $KOTLIN_LSP_DIR/kotlin-lsp.sh
    ```

2. Create a symlink inside your `PATH` to `kotlin-lsp.sh` script, e.g.:

    ```sh
    ln -s $KOTLIN_LSP_DIR/kotlin-lsp.sh $HOME/.local/bin/kotlin-ls
    ```

3. Configure [languages.toml](https://docs.helix-editor.com/languages.html) e.g:

    ```toml
    [language-server.kotlin]
    command = "kotlin-ls"
    args = ["--stdio"]
    
    [[language]]
    name = "kotlin"
    language-servers = [ "kotlin" ]
    ```
  
