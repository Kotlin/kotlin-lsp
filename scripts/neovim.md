# neovim setup

## stdio way

1. Ensure socat and netcat are installed

2. Create a symlink inside your PATH to run.sh script, e.g.:

    ```sh
    ln -s $KOTLIN_LSP_DIR/kotlin-lsp.sh $HOME/.local/bin/kotlin-ls
    ```

3. Configure [nvim.lsp](https://neovim.io/doc/user/lsp.html) e.g:
    ```lua
    {
      cmd = { "kotlin-ls", "--stdio" },
      single_file_support = true,
      filetypes = { "kotlin" },
      root_markers = { "build.gradle", "build.gradle.kts", "pom.xml" },
    }
    ```

## tcp way

Requires manual launch of language server

1. launch language server with kotlin-lsp.sh script

2. Configure [nvim.lsp](https://neovim.io/doc/user/lsp.html) e.g:
    ```lua
    {
      cmd = vim.lsp.rpc.connect('127.0.0.1', tonumber(9999))
      single_file_support = true,
      filetypes = { "kotlin" },
      root_markers = { "build.gradle", "build.gradle.kts", "pom.xml" },
    }
    ```
   