# @jetbrains/vscode-testing

Implement [`TestLanguage`](src/testLanguage.ts) to add discovery and launches.
This package supplies the Testing view, gutter icons, results, and reruns.

## Add a language

The complete client example below adds Run and Debug for a fictional language, `Example`.
Replace its commands and adapter fields with your own; the language server, compiler, and debug adapter must already exist.
Paths start at `language-server/`. Production example: [`vscode-jvm-testing`](../vscode-jvm-testing).
¡
### 1. Implement the server contract

The file discovery command enables the test controller.
Register these `workspace/executeCommand` commands in `executeCommandProvider.commands`:

| Command | Arguments array | Result |
| --- | --- | --- |
| `intellij.example.discoverTestsInFile` | `[{ "uri": "file:///work/app/math_test.example" }]` | Suites and their tests as `TestItemDto[]` |
| `intellij.example.discoverTestModules` | `[]` | Module names, such as `["app"]` |
| `intellij.example.discoverTestsInModule` | `["app"]` | Suites without their tests as `TestItemDto[]` |
| `intellij.example.resolveTestLaunch` | One request object, shown below | One launch per process |

File discovery response for `math_test.example`:

```json
[
  {
    "id": "suite:math",
    "kind": "SUITE",
    "displayName": "Math",
    "uri": "file:///work/app/math_test.example",
    "range": {
      "start": { "line": 0, "character": 0 },
      "end": { "line": 8, "character": 1 }
    },
    "moduleName": "app",
    "groups": [{ "id": "arithmetic", "label": "arithmetic" }],
    "location": "example:suite://math"
  },
  {
    "id": "test:math/add",
    "kind": "TEST",
    "displayName": "adds numbers",
    "uri": "file:///work/app/math_test.example",
    "range": {
      "start": { "line": 2, "character": 2 },
      "end": { "line": 4, "character": 3 }
    },
    "parentId": "suite:math",
    "moduleName": "app",
    "groups": [],
    "location": "example:test://math/add"
  }
]
```

Module discovery returns only the suite. The tree is `app → arithmetic → Math → adds numbers`.
Rules for [`TestItemDto`](src/testProtocol.ts):

- Keep IDs stable and locations unique within each module.
- Match `location` with the runner's `locationHint`.
- Use zero-based ranges.
- Report only what the server can launch: no abstract suites, no tests of a framework without a runner.

Launch request:

```json
{
  "command": "intellij.example.resolveTestLaunch",
  "arguments": [{
    "uri": "file:///work/app/math_test.example",
    "moduleName": "app",
    "testIds": ["test:math/add"],
    "uniqueIds": []
  }]
}
```

The server builds the module and returns:

```json
[
  {
    "program": "/work/app/out/example-test-runner",
    "cwd": "/work/app",
    "args": ["--teamcity", "--test", "math/add"]
  }
]
```

Expand suite IDs to their tests; return one launch per framework.
A command error or an empty launch list fails the group.

To rerun one parameterized invocation, send this request object:

```json
{
  "uri": "file:///work/app/math_test.example",
  "moduleName": "app",
  "testIds": [],
  "uniqueIds": [{ "ownerId": "test:math/add", "uniqueId": "add:case-1" }]
}
```

Resolve both `testIds` and `uniqueIds`.
Here, `ownerId` selects the test and `uniqueId` selects its invocation: `--teamcity --test math/add --case add:case-1`.

### 2. Create the package

```text
community/vscode-example-testing/
  package.json
  tsconfig.json
  OWNERSHIP
  src/
    exampleTestLanguage.ts
    exampleTestLanguage.test.ts
    module.ts
```

`package.json`:

```json
{
  "name": "@jetbrains/vscode-example-testing",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "exports": { ".": "./src/module.ts" },
  "scripts": {
    "test": "NODE_OPTIONS=\"--import @jetbrains/vscode-testing/test/vscode-stub\" node --import tsx --test \"src/**/*.test.ts\"",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@jetbrains/vscode-extension-core": "workspace:*",
    "@jetbrains/vscode-testing": "workspace:*"
  },
  "devDependencies": {
    "@types/node": "catalog:",
    "@types/vscode": "catalog:",
    "tsx": "catalog:",
    "typescript": "catalog:"
  }
}
```

`tsconfig.json`:

```json
{
  "extends": "../tsconfig.base.json",
  "compilerOptions": { "noEmit": true, "types": ["node"] },
  "include": ["src"],
  "exclude": ["node_modules", "out"]
}
```

Set `OWNERSHIP` using the [ownership instructions](../../../.ownership/README.md).

### 3. Implement discovery and launches

`src/exampleTestLanguage.ts`:

```ts
import { Uri } from 'vscode';
import { getLspClient, sendLspCommand } from '@jetbrains/vscode-extension-core';
import {
  debugAdapterRunner,
  lspDiscovery,
  type StackFrameParser,
  type TestFrame,
  type TestLanguage,
  type TestLaunchConfig,
  type TestNodeId,
  type TestProfile,
  type TestRunGroup,
  type TestRunner,
  type UniqueTestNode,
} from '@jetbrains/vscode-testing';

type ExampleProfile = 'run' | 'debug';

interface LaunchRequest {
  readonly uri: string;
  readonly moduleName: string | null;
  readonly testIds: readonly TestNodeId[];
  readonly uniqueIds: readonly UniqueTestNode[];
}

interface ExampleLaunchConfig extends TestLaunchConfig {
  readonly type: 'example';
  readonly program: string;
  readonly cwd: string;
  readonly args: string[];
}

type LaunchServer = (request: LaunchRequest) => Promise<unknown>;

const launchServer: LaunchServer = (request) => {
  const client = getLspClient();
  if (!client) throw new Error('The language server is not running.');
  return sendLspCommand<unknown>(client, 'intellij.example.resolveTestLaunch', [request]);
};

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

function parseLaunches(value: unknown): ExampleLaunchConfig[] {
  if (!Array.isArray(value)) throw new Error('The server returned an invalid launch list.');
  return value.map((item: unknown) => {
    if (
      typeof item !== 'object' || item === null ||
      !('program' in item) || typeof item.program !== 'string' ||
      !('cwd' in item) || typeof item.cwd !== 'string' ||
      !('args' in item) || !isStringArray(item.args)
    ) {
      throw new Error('The server returned an invalid test launch.');
    }
    return {
      type: 'example',
      request: 'launch',
      program: item.program,
      cwd: item.cwd,
      args: item.args,
    };
  });
}

const stackFrames: StackFrameParser = (stacktrace) => {
  const frames: TestFrame[] = [];
  for (const text of stacktrace.split('\n')) {
    const match = /^at (.+) \((file:\/\/.*):(\d+)\)$/.exec(text.trim());
    if (!match) continue;
    const [, label, uri, line] = match;
    frames.push({ label, uri: Uri.parse(uri), line: Number(line) });
  }
  return frames;
};

export class ExampleTestLanguage implements TestLanguage<ExampleProfile> {
  readonly controller = { id: 'intellijExampleTest', label: 'Example Tests' };
  readonly profiles: readonly TestProfile<ExampleProfile>[] = [
    { id: 'run', label: 'Run', button: 'run', nodes: 'any' },
    { id: 'debug', label: 'Debug', button: 'debug', nodes: 'any' },
  ];
  readonly discovery = lspDiscovery({
    commands: {
      discoverTestsInFile: 'intellij.example.discoverTestsInFile',
      discoverTestModules: 'intellij.example.discoverTestModules',
      discoverTestsInModule: 'intellij.example.discoverTestsInModule',
    },
    languageIds: new Set(['example']),
    sourceGlob: '**/*.example',
  });

  constructor(private readonly server: LaunchServer = launchServer) {}

  startRun(profile: ExampleProfile): TestRunner {
    return debugAdapterRunner({
      mode: profile,
      launches: ({ group }) => this.resolveLaunches(group),
      stackFrames,
    });
  }

  async resolveLaunches(group: TestRunGroup): Promise<ExampleLaunchConfig[]> {
    const response = await this.server({
      uri: group.uri.toString(),
      moduleName: group.moduleName,
      testIds: group.testIds,
      uniqueIds: group.uniqueIds,
    });
    return parseLaunches(response);
  }
}
```

Adapt the stack parser to your runner's format; [`TestFrame.line`](src/testFailure.ts) is one-based.

`src/module.ts`:

```ts
import { testingModule } from '@jetbrains/vscode-testing';
import { ExampleTestLanguage } from './exampleTestLanguage';

export default testingModule(new ExampleTestLanguage());
```

`startRun` runs once per request; its runner processes module groups in sequence.
Keep client build state there, as [JVM tests](../vscode-jvm-testing/src/jvmTestLanguage.ts) do.
`debugAdapterRunner` handles Run, Debug, and cancellation. The adapter must preserve the generated `testRunToken`.

### 4. Connect the runner output

Forward process output as DAP `output` events; send `exited` with `exitCode` before termination.
The runner emits one TeamCity message per line:

```text
##teamcity[testSuiteStarted name='Math' nodeId='s1' locationHint='example:suite://math']
##teamcity[testStarted name='adds numbers' nodeId='t1' parentNodeId='s1' locationHint='example:test://math/add']
##teamcity[testFailed name='adds numbers' nodeId='t1' message='Unexpected sum' expected='4' actual='5' details='at adds numbers (file:///work/app/math_test.example:3)']
##teamcity[testFinished name='adds numbers' nodeId='t1' duration='12']
##teamcity[testSuiteFinished name='Math' nodeId='s1']
```

- Label: the trimmed `name` of `testStarted` or `testSuiteStarted` becomes the label of the node.
- Pass: omit `testFailed`. Skip: use `testIgnored`. Execution error: add `error='true'` to `testFailed`.
- Output: use `testStdOut` or `testStdErr` with `nodeId` and `out`. Durations use milliseconds.
- Escape attribute values: `|` → `||`, `'` → `|'`, newline → `|n`, carriage return → `|r`, brackets → `|[`/`|]`.

For parameterized tests, report each invocation under its discovered test:

```text
##teamcity[testSuiteStarted name='Math' nodeId='s1' locationHint='example:suite://math']
##teamcity[testSuiteStarted name='adds numbers' nodeId='t1' parentNodeId='s1' locationHint='example:test://math/add']
##teamcity[testStarted name='case 1' nodeId='add:case-1' parentNodeId='t1']
##teamcity[testFinished name='case 1' nodeId='add:case-1' duration='12']
##teamcity[testSuiteFinished name='adds numbers' nodeId='t1']
##teamcity[testSuiteFinished name='Math' nodeId='s1']
```

Keep invocation IDs stable. Reruns use the `ownerId` and `uniqueId` from step 1.

### 5. Register the package in an extension

Add `community/vscode-example-testing` to `pnpm-workspace.yaml`.
Merge this dependency into the extension's `package.json`:

```json
{
  "dependencies": {
    "@jetbrains/vscode-example-testing": "workspace:*"
  }
}
```

For `intellij-vscode`, also update:

| File | Add |
| --- | --- |
| Extension `package.json` | Append `&& pnpm --filter @jetbrains/vscode-example-testing test` to the test script. |
| `tsconfig.json` → `include` | `../community/vscode-example-testing/src` |
| `rspack.config.ts` → `sourceDirs` | `path.join(communityDir, 'vscode-example-testing/src')` |

Import the module in the extension entry point:

```ts
import exampleTestingModule from '@jetbrains/vscode-example-testing';
```

Add it to `activateExtension` → `modules`, preserving existing entries:

```ts
modules: [kotlinModule, javaModule, jvmTestingModule, exampleTestingModule, bazelModule],
```

Register the module before the server starts. The extension must already register the language and debug adapter.
For other extensions, follow their `vscode-jvm-testing` entries. Run `pnpm install`.

### 6. Test the integration

`src/exampleTestLanguage.test.ts`:

```ts
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Uri } from 'vscode';
import type { TestNodeId, TestRunGroup } from '@jetbrains/vscode-testing';
import { ExampleTestLanguage } from './exampleTestLanguage';

const group: TestRunGroup = {
  moduleName: 'app',
  testIds: [],
  uniqueIds: [{ ownerId: 'test:math/add' as TestNodeId, uniqueId: 'add:case-1' }],
  uri: Uri.parse('file:///work/app/math_test.example'),
  name: 'case 1',
};

test('passes the runtime selection to the server and creates an adapter launch', async () => {
  const language = new ExampleTestLanguage(async (request) => {
    assert.deepEqual(request, {
      uri: 'file:///work/app/math_test.example',
      moduleName: 'app',
      testIds: [],
      uniqueIds: [{ ownerId: 'test:math/add', uniqueId: 'add:case-1' }],
    });
    return [{
      program: '/work/app/out/example-test-runner',
      cwd: '/work/app',
      args: ['--teamcity', '--test', 'math/add', '--case', 'add:case-1'],
    }];
  });

  assert.deepEqual(await language.resolveLaunches(group), [{
    type: 'example',
    request: 'launch',
    program: '/work/app/out/example-test-runner',
    cwd: '/work/app',
    args: ['--teamcity', '--test', 'math/add', '--case', 'add:case-1'],
  }]);
});

test('rejects a malformed launch response', async () => {
  const language = new ExampleTestLanguage(async () => [{ program: 42 }]);
  await assert.rejects(language.resolveLaunches(group), /invalid test launch/);
});

test('preserves a server build error', async () => {
  const language = new ExampleTestLanguage(async () => {
    throw new Error('Compilation failed.');
  });
  await assert.rejects(language.resolveLaunches(group), /Compilation failed/);
});
```

Run:

```sh
pnpm --filter @jetbrains/vscode-example-testing test
pnpm --filter @jetbrains/vscode-example-testing typecheck
pnpm --filter @jetbrains/vscode-testing test
pnpm --filter @jetbrains/vscode-testing typecheck
node_modules/.bin/tsc --noEmit -p intellij-vscode/tsconfig.json
```

Use the target extension's `tsconfig.json`.
Test server discovery, suite and invocation selection, and build failures.
In VS Code, check refresh, selection, breakpoints, failure links, invocation reruns, cancellation, and server restart.

## Other runners, profiles, and coverage

These optional features are outside the Run/Debug example:

| Need | Change |
| --- | --- |
| Another discovery transport | Implement `TestDiscovery`. |
| Another runner or output format | Implement `TestRunner.run(input)` with [`input.report`](src/testRunReport.ts); handle `input.token` cancellation. |
| Tests created during a run | Use `report.atLocation` for the parent and `report.runtimeChild` for the child. |
| A profile for selected tests | Use `nodes: 'tagged'` and add the profile ID to each applicable node's `tags`. |
| Coverage | Call `report.coverage(file)` with [`TestFileCoverage`](src/testCoverage.ts). |

The first profile for each `button` is the default. Omit Debug profiles without a debugger.

## Add a JVM test framework

A new framework needs no client change if it uses the existing runner and location format.

1. Implement `LSJvmTestFrameworkFeature` in `features-impl/jvm-testing`. Use `LSTestNgTestFrameworkFeature` as an example.
2. Register it with `<jvmTestFramework implementation="..."/>` in `resources/META-INF/language-server/features/jvm/testing.xml` within that module.
3. Emit `java:suite://<binary class name>` for suites and `java:test://<binary class name>/<method>` for tests.

Discovery skips a recognized framework until a feature supplies launch support.
