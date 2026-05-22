# Development

The dev build runs directly from this package. Generated files stay in ignored folders such as `out/` and `grammars/`.

### Bootstrap, run, watch

From this directory, run:

```sh
pnpm install
```

In VS Code, press F5 (`Extension`). The launch config triggers the `watch` task. After F5: edit a file in `src/`, Rspack rebuilds, hit `Cmd+R` in the development host.

### Test

```sh
pnpm test
```

Runs from this directory. In VS Code, `Tasks: Run Test Task` runs the `test` task.

### Build VSIX

After server artifacts are built, run:

```sh
./build.sh
```

When this package is built inside a pnpm workspace, enable pnpm 11 once:

```sh
corepack enable
corepack prepare pnpm@11.4.0 --activate
```
