# neovim setup

## `nvim-lspconfig` setup

There is a preset configuration for using `kotlin-lsp` with Neovim in the
official [`nvim-lspconfig`](https://github.com/neovim/nvim-lspconfig) plugin.

To use it, just enable it in your configuration and ensure `kotlin-lsp` is
available in `PATH` (or override the `cmd` option to point at the right place).

```lua
-- enable the language server
vim.lsp.enable('kotlin-lsp')

-- configure its options
vim.lsp.config('kotlin-lsp', {
    single_file_support = false,
})
```

## stdio way

1. Ensure socat and netcat are installed

2. Ensure `kotlin-lsp.sh` is executable
   
    ```sh
    chmod +x $KOTLIN_LSP_DIR/kotlin-lsp.sh
    ```

3. Create a symlink inside your `PATH` to `kotlin-lsp.sh` script, e.g.:

    ```sh
    ln -s $KOTLIN_LSP_DIR/kotlin-lsp.sh $HOME/.local/bin/kotlin-ls
    ```

4. Configure [nvim.lsp](https://neovim.io/doc/user/lsp.html) e.g:
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

1. launch language server with `kotlin-lsp.sh` script

2. Configure [nvim.lsp](https://neovim.io/doc/user/lsp.html) e.g:
    ```lua
    {
      cmd = vim.lsp.rpc.connect('127.0.0.1', tonumber(9999)),
      single_file_support = true,
      filetypes = { "kotlin" },
      root_markers = { "build.gradle", "build.gradle.kts", "pom.xml" },
    }
    ```
   
