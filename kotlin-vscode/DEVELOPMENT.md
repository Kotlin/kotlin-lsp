# Development

The dev build is sandboxed in `out/dev/` — the source tree stays clean, the IntelliJ overlay can be applied non-destructively, and a single ignored folder (`out/`) covers everything generated.

### Bootstrap, run, watch

In VS Code, press F5 (`Extension`). The launch config triggers the `dev-watch` task, which depends on `dev-bootstrap`, which runs `dev-setup.sh`. The script is idempotent — re-running is fast.

What `dev-setup.sh` does:

- Mirrors static files into `out/dev/`.
- Symlinks `src/` (and `testSources/`) so webpack-watch picks up source edits live.
- Runs `npm install` inside `out/dev/` (npm itself is a no-op when nothing changed).

After F5: edit a file in `src/`, webpack rebuilds, hit `Cmd+R` in the development host.

### Test

```sh
npm test
```

Runs from this directory. It bootstraps `out/dev/` and runs all tests. In VS Code, `Tasks: Run Test Task` does the same via the `dev-test` task.