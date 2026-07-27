# Vendored grammar artifacts

## tree-sitter-kotlin.wasm

- **Source repo:** https://github.com/exoego/tree-sitter-kotlin (fork of fwcd/tree-sitter-kotlin, PR #276)
- **Commit:** `de7c72c509b6403da83a23ed0485576bda5531a5`
- **Build command:**
  ```
  tree-sitter build --wasm <path-to-tree-sitter-kotlin-source>
  ```
  Requires Docker with `emscripten/emsdk` image. The package's pre-generated `src/parser.c` is used; no `tree-sitter generate` step needed.

To rebuild after a source update, check out the desired commit of the source repo, then run the build command pointing at that checkout:
```
tree-sitter build --wasm <path-to-tree-sitter-kotlin-source>
```
Copy the generated `tree-sitter-kotlin.wasm` into this directory and update the **Commit** reference above.
