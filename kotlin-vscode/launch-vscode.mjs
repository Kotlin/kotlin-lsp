#!/usr/bin/env node
// Launch an isolated VS Code instance wired to a locally running LSP server.
//
// Compiles the dev extension for the requested bundle (rspack, runs from the
// package sources -- no bundled server), creates an isolated VS Code profile
// preconfigured with `intellij.dev.serverPort`, and opens it.
//
// Usage: node launch-vscode.mjs --bundle-type=<kotlin-server|intellij-server|intellij-server-experimental> [--port=9999] [workspace-folder]
//
// Cross-platform (macOS/Linux/Windows): no shell-specific behavior.

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
// kotlin-vscode -> community -> language-server (the pnpm workspace root).
const WORKSPACE_ROOT = path.resolve(SCRIPT_DIR, '..', '..');
// language-server -> community -> kotlin-vscode -> <repo>; isolated profiles live under <repo>/out.
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..', '..');

// pnpm version used only for the corepack fallback (mirrors the workspace README).
const PNPM_VERSION = '11.4.0';

// Each bundle type is a separate pnpm workspace package, relative to WORKSPACE_ROOT.
const BUNDLE_PACKAGE_DIRS = {
    'kotlin-server': 'community/kotlin-vscode',
    'intellij-server': 'intellij-vscode/intellij-server',
    'intellij-server-experimental': 'intellij-vscode/intellij-server-experimental',
};

const IS_WIN = process.platform === 'win32';

function green(message) {
    return `\x1b[32m${message}\x1b[0m`;
}

function fail(message) {
    console.error(message);
    process.exit(1);
}

// Run a command, inheriting stdio. On Windows we go through the shell so that
// `.cmd` shims (pnpm, corepack, code) resolve, quoting args that need it.
function run(file, args, opts = {}) {
    let spawnFile = file;
    let spawnArgs = args;
    if (IS_WIN) {
        spawnArgs = args.map((a) => (/[\s"]/.test(a) ? `"${a.replace(/"/g, '\\"')}"` : a));
    }
    const res = spawnSync(spawnFile, spawnArgs, { stdio: 'inherit', shell: IS_WIN, ...opts });
    if (res.error) throw res.error;
    return res.status ?? 0;
}

// True if `<file> --version` exits 0 (i.e. the executable is resolvable).
function isAvailable(file) {
    const res = spawnSync(file, ['--version'], { stdio: 'ignore', shell: IS_WIN });
    return !res.error && res.status === 0;
}

// Resolve a pnpm runner. A runner is `{ file, prefix }`; pnpm is invoked as
// `file [...prefix, ...pnpmArgs]`. Prefer pnpm on PATH, then corepack, then
// `npm exec` (npm always ships with Node, so this works on a bare checkout).
function resolvePnpm() {
    for (const name of IS_WIN ? ['pnpm.cmd', 'pnpm'] : ['pnpm']) {
        if (isAvailable(name)) return { file: name, prefix: [] };
    }
    for (const name of IS_WIN ? ['corepack.cmd', 'corepack'] : ['corepack']) {
        if (isAvailable(name)) return { file: name, prefix: [`pnpm@${PNPM_VERSION}`] };
    }
    for (const name of IS_WIN ? ['npm.cmd', 'npm'] : ['npm']) {
        if (isAvailable(name)) return { file: name, prefix: ['exec', '--yes', '--package', `pnpm@${PNPM_VERSION}`, '--', 'pnpm'] };
    }
    return null;
}

// Resolve the VS Code CLI across platforms.
function resolveCodeBin() {
    const onPath = IS_WIN ? 'code.cmd' : 'code';
    if (isAvailable(onPath)) return onPath;

    const candidates = [];
    if (process.platform === 'darwin') {
        candidates.push('/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code');
    } else if (process.platform === 'linux') {
        candidates.push('/usr/share/code/bin/code', '/snap/bin/code', '/usr/bin/code');
    } else if (IS_WIN) {
        const localAppData = process.env.LOCALAPPDATA;
        const programFiles = process.env.ProgramFiles;
        if (localAppData) candidates.push(path.join(localAppData, 'Programs', 'Microsoft VS Code', 'bin', 'code.cmd'));
        if (programFiles) candidates.push(path.join(programFiles, 'Microsoft VS Code', 'bin', 'code.cmd'));
    }
    return candidates.find((c) => fs.existsSync(c)) ?? null;
}

// --- Parse arguments ---------------------------------------------------------

let bundleType = '';
let serverPort = Number(process.env.SERVER_PORT) || 9999;
let workspace = '';
for (const arg of process.argv.slice(2)) {
    if (arg.startsWith('--bundle-type=')) bundleType = arg.slice('--bundle-type='.length);
    else if (arg.startsWith('--port=')) serverPort = Number(arg.slice('--port='.length));
    else if (arg.startsWith('-')) fail(`Unknown option: ${arg}`);
    else workspace = arg;
}

const bundleKeys = Object.keys(BUNDLE_PACKAGE_DIRS).join('|');
if (!bundleType) fail(`--bundle-type=<${bundleKeys}> is required`);
const bundleRelDir = BUNDLE_PACKAGE_DIRS[bundleType];
if (!bundleRelDir) fail(`Unknown --bundle-type '${bundleType}'. Expected one of: ${bundleKeys}`);

const extensionDir = path.join(WORKSPACE_ROOT, bundleRelDir);
const isoDir = path.join(REPO_ROOT, 'out', 'language-server', `vscode-${bundleType}-lsp`);
const userDataDir = path.join(isoDir, 'user-data');
const extensionsDir = path.join(isoDir, 'extensions');

// --- Resolve toolchain -------------------------------------------------------

const codeBin = resolveCodeBin();
if (!codeBin) {
    console.error("Error: 'code' CLI not found on PATH.");
    console.error('In VS Code, run \'Shell Command: Install "code" command in PATH\', then retry.');
    process.exit(1);
}

const pnpm = resolvePnpm();
if (!pnpm) {
    fail("Error: none of 'pnpm', 'corepack', or 'npm' was found on PATH. Install Node.js (which provides npm), then retry.");
}
if (pnpm.file.startsWith('npm')) {
    console.log(green(`pnpm not found; running it on demand via 'npm exec pnpm@${PNPM_VERSION}' (install pnpm for faster launches)`));
}

// --- 1. Install workspace deps for this bundle (+ its workspace deps) --------
// Filtered (brace syntax `{./dir}...` -- a bare `./dir...` filter does NOT
// include workspace deps in pnpm 11) so launching kotlin-server / intellij-server
// does NOT build intellij-server-experimental's / datagrip-server's native
// `tree-sitter-sql` (node-gyp, no prebuilds -- fails on Node >=24). Those bundles
// pull it in via the filter and need Node 22.x to compile it. The install is fast
// and idempotent when nothing changed, so we run it on every launch (no
// node_modules guard -- that would skip a second bundle's deps after the first
// launch). The compile step below must keep pnpm's deps re-check disabled, or it
// would fall back to a full unfiltered install that rebuilds `tree-sitter-sql`.
//
// `--config.confirm-modules-purge=false`: narrowing from an existing full
// install (e.g. a checkout that ran an older version of this script) makes pnpm
// purge node_modules, which otherwise aborts in a non-TTY console such as the
// IDE run-configuration output.

console.log(green(`Installing dependencies for ${bundleType} in ${WORKSPACE_ROOT}`));
if (run(pnpm.file, [...pnpm.prefix, '--dir', WORKSPACE_ROOT, '--config.confirm-modules-purge=false', 'install', '--filter', `{./${bundleRelDir}}...`], { cwd: WORKSPACE_ROOT }) !== 0) {
    fail('Error: dependency install failed.');
}

// --- 2. Compile the dev extension (from package sources) ---------------------

console.log(green(`Compiling extension (${bundleType}) in ${extensionDir}`));
// `--config.verify-deps-before-run=false`: step 1 already installed this bundle's
// subtree; without this, pnpm's deps re-check would run a full unfiltered
// workspace install and rebuild the experimental/datagrip `tree-sitter-sql`.
if (run(pnpm.file, [...pnpm.prefix, '--dir', extensionDir, '--config.verify-deps-before-run=false', 'run', 'compile']) !== 0) {
    fail('Error: extension compile failed.');
}

// --- 3. Preconfigure the isolated VS Code profile (idempotent merge) ---------

const userDir = path.join(userDataDir, 'User');
fs.mkdirSync(userDir, { recursive: true });
fs.mkdirSync(extensionsDir, { recursive: true });

const settingsFile = path.join(userDir, 'settings.json');
let settings = {};
try {
    settings = JSON.parse(fs.readFileSync(settingsFile, 'utf8'));
} catch {
    // No existing (or invalid) settings -- start fresh.
}
settings['intellij.dev.serverPort'] = serverPort;
fs.writeFileSync(settingsFile, `${JSON.stringify(settings, null, 2)}\n`);

const keybindingsFile = path.join(userDir, 'keybindings.json');
if (!fs.existsSync(keybindingsFile)) {
    const keybindings = [{ key: 'cmd+shift+r', command: 'workbench.action.reloadWindow' }];
    fs.writeFileSync(keybindingsFile, `${JSON.stringify(keybindings, null, 2)}\n`);
}

// --- 4. Launch the isolated VS Code instance ---------------------------------

console.log(green(`Launching VS Code (port ${serverPort}, profile ${isoDir})`));
const codeArgs = [
    `--extensionDevelopmentPath=${extensionDir}`,
    `--user-data-dir=${userDataDir}`,
    `--extensions-dir=${extensionsDir}`,
    '--new-window',
];
if (workspace) codeArgs.push(workspace);

process.exit(run(codeBin, codeArgs));
