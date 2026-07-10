# Vendored grammar artifacts

## tree-sitter-kotlin.wasm

- **Source repo:** https://github.com/exoego/tree-sitter-kotlin (fork of fwcd/tree-sitter-kotlin, PR #276)
- **Commit:** `de7c72c509b6403da83a23ed0485576bda5531a5`
- **Build command:**
  ```
  tree-sitter build --wasm <path-to-tree-sitter-kotlin-source>
  ```
  Requires Docker with `emscripten/emsdk` image. The package's pre-generated `src/parser.c` is used; no `tree-sitter generate` step needed.

To rebuild after a dependency bump, update the commit SHA in `pnpm-workspace.yaml` (catalog entry `tree-sitter-kotlin`), run `pnpm install`, then re-run the build command pointing at the updated source in `node_modules/.pnpm/tree-sitter-kotlin@.../node_modules/tree-sitter-kotlin`.
