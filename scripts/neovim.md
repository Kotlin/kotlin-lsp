# Neovim setup

## Common steps

These steps are common for both ways.

1. [Install `kotlin-lsp` CLI](../README.md#install-kotlin-lsp-cli)
2. Make sure that the `kotlin-lsp` binary is on your `$PATH`

## `nvim-lspconfig` way

There is a preset configuration for using `kotlin-lsp` with Neovim in the official [`nvim-lspconfig`](https://github.com/neovim/nvim-lspconfig) plugin.

```lua
-- enable the language server
vim.lsp.enable('kotlin_lsp')

-- configure language server's options
vim.lsp.config('kotlin_lsp', {
    single_file_support = false,
})
```

## stdio way

1. Ensure `socat` and `netcat` are installed
2. Ensure `kotlin-lsp.sh` is executable

    ```sh
    chmod +x $KOTLIN_LSP_DIR/kotlin-lsp.sh
    ```

3. Create a symlink inside your `$PATH` to `kotlin-lsp.sh` script, e.g.:

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

1. Make sure the `kotlin-lsp` is running and listening on port 9999 (the default).
2. Configure [nvim.lsp](https://neovim.io/doc/user/lsp.html) e.g:

    ```lua
    {
      cmd = vim.lsp.rpc.connect('127.0.0.1', tonumber(9999))
      single_file_support = true,
      filetypes = { "kotlin" },
      root_markers = { "build.gradle", "build.gradle.kts", "pom.xml" },
    }
    ```
