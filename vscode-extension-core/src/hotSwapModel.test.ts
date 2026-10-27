// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, test } from 'node:test';
import {
  HOT_SWAP_COMMAND,
  HOT_SWAP_MODES,
  HOT_SWAP_SETTING,
  HOT_SWAP_SUPPORTED_CONTEXT,
  HotSwapBatches,
  type HotSwapTimers,
  HotSwapSessions,
  SAVE_DEBOUNCE_MS,
  SaveCoalescer,
  hotSwapCapabilityOf,
  hotSwapContextValue,
  hotSwapErrorOutcome,
  hotSwapModeOf,
  hotSwapOutcome,
  isHotSwapDocument,
  namedHotSwapOutcome,
} from './hotSwapModel';

describe('hotSwapOutcome', () => {
  test('reloaded shows the class count in the status bar, with no other text', () => {
    assert.deepEqual(
      hotSwapOutcome({
        status: 'reloaded',
        reloadedClasses: 3,
        messages: [{ severity: 'info', text: '3 classes reloaded' }],
      }),
      { kind: 'status', text: 'Reloaded 3 classes', offerRestart: false },
    );
    assert.equal(
      hotSwapOutcome({ status: 'reloaded', reloadedClasses: 1, messages: [] }).text,
      'Reloaded 1 class',
    );
  });

  // A success can carry a warning, e.g. modules no build tool can build: their classes compiled before still reload.
  // A status bar message would hide it, so the user gets a warning; a restart does not fix it, so none is offered.
  test('reloaded with a warning or an error warns with the summary and the first of them, without a restart', () => {
    assert.deepEqual(
      hotSwapOutcome({
        status: 'reloaded',
        reloadedClasses: 2,
        messages: [
          { severity: 'info', text: '2 classes reloaded' },
          {
            severity: 'warning',
            text: 'No build tool can build modules a, b; reloading their classes already compiled\n',
          },
          { severity: 'error', text: 'second' },
        ],
      }),
      {
        kind: 'warning',
        text: 'Reloaded 2 classes: No build tool can build modules a, b; reloading their classes already compiled',
        offerRestart: false,
      },
    );
    assert.deepEqual(
      hotSwapOutcome({
        status: 'noChanges',
        messages: [{ severity: 'error', text: 'module c failed to build' }],
      }),
      {
        kind: 'warning',
        text: 'No changed classes to reload: module c failed to build',
        offerRestart: false,
      },
    );
  });

  test('a success with only info or unknown severities is a status', () => {
    assert.equal(
      hotSwapOutcome({
        status: 'reloaded',
        reloadedClasses: 1,
        messages: [
          { severity: 'info', text: 'i' },
          { severity: 'hint', text: 'h' },
        ],
      }).kind,
      'status',
    );
  });

  test('no changes is a status', () => {
    assert.deepEqual(hotSwapOutcome({ status: 'noChanges', reloadedClasses: 0, messages: [] }), {
      kind: 'status',
      text: 'No changed classes to reload',
      offerRestart: false,
    });
  });

  // A restart builds again and fails the same way.
  test('a failed build warns with the first error and offers no restart', () => {
    assert.deepEqual(
      hotSwapOutcome({
        status: 'buildFailed',
        reloadedClasses: 0,
        messages: [
          { severity: 'info', text: 'Building :app' },
          { severity: 'error', text: 'App.java:3: error: ; expected\n' },
          { severity: 'error', text: 'second' },
        ],
      }),
      {
        kind: 'warning',
        text: 'Reload failed: App.java:3: error: ; expected',
        offerRestart: false,
      },
    );
  });

  // The VM refused the new code (e.g. a new method): a new session runs it.
  test('a reload the VM refused offers a restart', () => {
    assert.deepEqual(
      hotSwapOutcome({
        status: 'failed',
        messages: [{ severity: 'error', text: 'add method not implemented' }],
      }),
      { kind: 'warning', text: 'Reload failed: add method not implemented', offerRestart: true },
    );
  });

  test('a session that cannot reload offers no restart', () => {
    assert.equal(hotSwapOutcome({ status: 'unsupported' }).offerRestart, false);
  });

  test('without an error message the first warning, then any message, then the status explains the failure', () => {
    assert.equal(
      hotSwapOutcome({
        status: 'failed',
        messages: [
          { severity: 'info', text: 'info line' },
          { severity: 'warning', text: 'schema change not implemented' },
        ],
      }).text,
      'Reload failed: schema change not implemented',
    );
    assert.equal(
      hotSwapOutcome({
        status: 'unsupported',
        messages: [{ severity: 'info', text: 'no class output' }],
      }).text,
      'Reload failed: no class output',
    );
    assert.equal(hotSwapOutcome({ status: 'buildFailed' }).text, 'Reload failed: the build failed');
    assert.equal(
      hotSwapOutcome({ status: 'failed' }).text,
      'Reload failed: the classes could not be reloaded',
    );
    assert.equal(
      hotSwapOutcome({ status: 'unsupported' }).text,
      'Reload failed: the debug session cannot reload classes',
    );
  });

  // The status is an open enum on the server: a newer server may send one this client does not know.
  test('an unknown status or no body is a failure without a restart', () => {
    assert.deepEqual(hotSwapOutcome({ status: 'partial' }), {
      kind: 'warning',
      text: 'Reload failed: unexpected status "partial"',
      offerRestart: false,
    });
    assert.deepEqual(hotSwapOutcome(undefined), {
      kind: 'warning',
      text: 'Reload failed: the debug adapter sent no answer',
      offerRestart: false,
    });
  });

  // `customRequest` throws when the response has `success=false`: no session, no adapter support, a reload running.
  test('a thrown DAP error warns with its message and offers no restart', () => {
    assert.deepEqual(hotSwapErrorOutcome(new Error('A hot swap is already running')), {
      kind: 'warning',
      text: 'Reload failed: A hot swap is already running',
      offerRestart: false,
    });
    assert.equal(hotSwapErrorOutcome('plain')?.text, 'Reload failed: plain');
  });

  // VS Code answers a pending request with `canceled` when the adapter stops: the user stopped the session.
  test('a request the stopped session never answered shows nothing', () => {
    assert.equal(hotSwapErrorOutcome(new Error('canceled')), undefined);
  });
});

describe('namedHotSwapOutcome', () => {
  test('names the session in front of the text, or keeps the outcome for a single session', () => {
    const outcome = hotSwapOutcome({ status: 'failed' });
    assert.deepEqual(namedHotSwapOutcome(outcome, 'App'), {
      ...outcome,
      text: 'App: Reload failed: the classes could not be reloaded',
    });
    assert.equal(namedHotSwapOutcome(outcome, undefined), outcome);
  });
});

describe('hotSwapModeOf', () => {
  test('a known value is the mode, anything else is the default', () => {
    for (const mode of HOT_SWAP_MODES) assert.equal(hotSwapModeOf(mode), mode);
    assert.equal(hotSwapModeOf(undefined), 'manual');
    assert.equal(hotSwapModeOf('always'), 'manual');
    assert.equal(hotSwapModeOf(42), 'manual');
  });
});

describe('isHotSwapDocument', () => {
  test('only JVM sources trigger a reload on save', () => {
    assert.equal(isHotSwapDocument('java'), true);
    assert.equal(isHotSwapDocument('kotlin'), true);
    assert.equal(isHotSwapDocument('json'), false);
    assert.equal(isHotSwapDocument('groovy'), false);
  });
});

describe('hotSwapContextValue', () => {
  test('the button shows for a capable session unless the setting is never', () => {
    assert.equal(hotSwapContextValue('manual', true), true);
    assert.equal(hotSwapContextValue('onSave', true), true);
    assert.equal(hotSwapContextValue('never', true), false);
    for (const mode of HOT_SWAP_MODES) assert.equal(hotSwapContextValue(mode, false), false, mode);
  });
});

describe('hotSwapCapabilityOf', () => {
  test('reads the capability from the initialize response', () => {
    const initialize = (body: unknown, success = true) => ({
      type: 'response',
      command: 'initialize',
      success,
      body,
    });
    assert.equal(hotSwapCapabilityOf(initialize({ supportsHotSwapRequest: true })), true);
    assert.equal(hotSwapCapabilityOf(initialize({ supportsHotSwapRequest: false })), false);
    // An older server has no such capability: the session is known and cannot reload.
    assert.equal(hotSwapCapabilityOf(initialize({ supportsThreadDump: true })), false);
    assert.equal(hotSwapCapabilityOf(initialize(undefined)), false);
    assert.equal(hotSwapCapabilityOf(initialize({ supportsHotSwapRequest: true }, false)), false);
  });

  test('every other message says nothing', () => {
    assert.equal(hotSwapCapabilityOf({ type: 'event', event: 'output', body: {} }), undefined);
    assert.equal(
      hotSwapCapabilityOf({ type: 'response', command: 'launch', success: true }),
      undefined,
    );
    assert.equal(hotSwapCapabilityOf(undefined), undefined);
  });
});

describe('HotSwapSessions', () => {
  test('tracks the capability per session id', () => {
    const sessions = new HotSwapSessions();
    assert.equal(sessions.supports(undefined), false);
    sessions.record('a', true);
    sessions.record('b', false);
    assert.equal(sessions.supports('a'), true);
    assert.equal(sessions.supports('b'), false);
    assert.equal(sessions.supports('c'), false);
    assert.equal(sessions.anySupported(), true);
    sessions.forget('a');
    assert.equal(sessions.supports('a'), false);
    assert.equal(sessions.anySupported(), false);
  });

  // Like IntelliJ, a reload goes to every debug session, not only the focused one.
  test('the capable sessions are every session that can reload, in initialize order', () => {
    const sessions = new HotSwapSessions();
    assert.deepEqual(sessions.capableSessions(), []);
    sessions.record('a', true);
    sessions.record('b', false);
    sessions.record('c', true);
    assert.deepEqual(sessions.capableSessions(), ['a', 'c']);
    sessions.record('a', true);
    assert.deepEqual(sessions.capableSessions(), ['c', 'a'], 'a restarted session goes last');
    sessions.forget('c');
    assert.deepEqual(sessions.capableSessions(), ['a']);
  });
});

/** Timers that fire only when the test says so. */
function fakeTimers() {
  let next = 0;
  const pending = new Map<number, { run: () => void; ms: number }>();
  const timers: HotSwapTimers = {
    set: (run, ms) => {
      next += 1;
      pending.set(next, { run, ms });
      return next;
    },
    clear: (handle) => void pending.delete(handle as number),
  };
  return {
    timers,
    pendingCount: () => pending.size,
    delays: () => [...pending.values()].map((entry) => entry.ms),
    fire: () => {
      const entries = [...pending.entries()];
      pending.clear();
      for (const [, entry] of entries) entry.run();
    },
  };
}

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

const settle = () => new Promise<void>((done) => setImmediate(done));

describe('SaveCoalescer', () => {
  test('saves in a burst run one reload after the debounce', async () => {
    const clock = fakeTimers();
    let runs = 0;
    const coalescer = new SaveCoalescer(async () => {
      runs += 1;
    }, clock.timers);
    coalescer.saved();
    coalescer.saved();
    coalescer.saved();
    assert.equal(clock.pendingCount(), 1);
    assert.deepEqual(clock.delays(), [SAVE_DEBOUNCE_MS]);
    assert.equal(runs, 0);
    clock.fire();
    await settle();
    assert.equal(runs, 1);
    assert.equal(clock.pendingCount(), 0);
  });

  test('saves during a reload run one more reload after it, not one per save', async () => {
    const clock = fakeTimers();
    const reloads: Array<ReturnType<typeof deferred>> = [];
    const coalescer = new SaveCoalescer(() => {
      const reload = deferred();
      reloads.push(reload);
      return reload.promise;
    }, clock.timers);
    coalescer.saved();
    clock.fire();
    assert.equal(reloads.length, 1);

    coalescer.saved();
    coalescer.saved();
    assert.equal(clock.pendingCount(), 0, 'no timer while a reload runs');

    reloads[0].resolve();
    await settle();
    assert.equal(clock.pendingCount(), 1, 'the rerun is debounced again');
    clock.fire();
    assert.equal(reloads.length, 2);
    reloads[1].resolve();
    await settle();
    assert.equal(clock.pendingCount(), 0);
    assert.equal(reloads.length, 2);
  });

  test('a failed reload does not stop the next one', async () => {
    const clock = fakeTimers();
    let runs = 0;
    const coalescer = new SaveCoalescer(async () => {
      runs += 1;
      throw new Error('boom');
    }, clock.timers);
    coalescer.saved();
    clock.fire();
    await settle();
    coalescer.saved();
    clock.fire();
    await settle();
    assert.equal(runs, 2);
  });

  test('discardPending drops the debounced reload and keeps later saves', async () => {
    const clock = fakeTimers();
    let runs = 0;
    const coalescer = new SaveCoalescer(async () => {
      runs += 1;
    }, clock.timers);
    coalescer.saved();
    coalescer.discardPending();
    assert.equal(clock.pendingCount(), 0);
    coalescer.saved();
    clock.fire();
    await settle();
    assert.equal(runs, 1);
  });

  test('dispose drops a pending reload and the rerun', async () => {
    const clock = fakeTimers();
    let runs = 0;
    const coalescer = new SaveCoalescer(async () => {
      runs += 1;
    }, clock.timers);
    coalescer.saved();
    coalescer.dispose();
    assert.equal(clock.pendingCount(), 0);
    coalescer.saved();
    assert.equal(clock.pendingCount(), 0);
    assert.equal(runs, 0);
  });
});

describe('HotSwapBatches', () => {
  // A second click while a batch runs must not start the next session's reload next to the running batch.
  test('start runs one batch at a time and drops a second one', async () => {
    const batches = new HotSwapBatches();
    const first = deferred();
    let runs = 0;
    const running = batches.start(() => {
      runs += 1;
      return first.promise;
    });
    assert.notEqual(running, undefined);
    assert.equal(
      batches.start(async () => {
        runs += 1;
      }),
      undefined,
      'a second batch is not run',
    );
    assert.equal(runs, 1);

    first.resolve();
    await settle();
    await batches.start(async () => {
      runs += 1;
    });
    assert.equal(runs, 2, 'a batch runs again once the running one ended');
  });

  // A save batch and a manual batch must not overlap: the save waits, then reloads every session itself.
  test('startAfterRunning runs once the running batch ended', async () => {
    const batches = new HotSwapBatches();
    const manual = deferred();
    const order: string[] = [];
    void batches.start(() => {
      order.push('manual');
      return manual.promise;
    });
    const save = batches.startAfterRunning(async () => {
      order.push('save');
      return 'saved';
    });
    await settle();
    assert.deepEqual(order, ['manual'], 'the save waits for the running batch');

    manual.resolve();
    assert.equal(await save, 'saved');
    assert.deepEqual(order, ['manual', 'save']);
  });

  test('startAfterRunning holds the slot while it runs', async () => {
    const batches = new HotSwapBatches();
    const answer = deferred();
    const save = batches.startAfterRunning(() => answer.promise);
    await settle();
    assert.equal(
      batches.start(async () => undefined),
      undefined,
      'a manual batch is dropped',
    );
    answer.resolve();
    await save;
  });

  test('startAfterRunning runs after a failed batch too, and at once when none runs', async () => {
    const batches = new HotSwapBatches();
    const failed = batches.start(() => Promise.reject(new Error('boom')));
    await assert.rejects(failed!);
    assert.equal(await batches.startAfterRunning(async () => 1), 1);

    let rejectRunning!: (error: Error) => void;
    void batches
      .start(() => new Promise<void>((_resolve, reject) => (rejectRunning = reject)))
      ?.catch(() => undefined);
    const after = batches.startAfterRunning(async () => 2);
    rejectRunning(new Error('boom'));
    assert.equal(await after, 2);
  });

  // The wiring of `hotSwap.ts`: a manual batch discards the pending save reload once, when it starts. A save during
  // the batch (between session A and session B) must still reload after it, or A keeps the old classes.
  test('a save during a manual batch reloads after it', async () => {
    const clock = fakeTimers();
    const batches = new HotSwapBatches();
    const order: string[] = [];
    const steps: Array<ReturnType<typeof deferred>> = [];
    const reloadSession = (name: string) => {
      order.push(name);
      const step = deferred();
      steps.push(step);
      return step.promise;
    };
    const reloadAll = async () => {
      await reloadSession('A');
      await reloadSession('B');
    };
    const coalescer = new SaveCoalescer(() => batches.startAfterRunning(reloadAll), clock.timers);
    const manual = () =>
      batches.start(() => {
        coalescer.discardPending();
        return reloadAll();
      });

    coalescer.saved();
    const batch = manual();
    assert.equal(clock.pendingCount(), 0, 'the batch covers the saves before it');

    coalescer.saved();
    steps[0].resolve();
    await settle();
    assert.deepEqual(order, ['A', 'B']);
    assert.equal(clock.pendingCount(), 1, 'the save during A is kept when B starts');
    assert.equal(manual(), undefined, 'a second click while the batch runs is dropped');

    clock.fire();
    await settle();
    assert.deepEqual(order, ['A', 'B'], 'the save batch waits for the manual one');
    steps[1].resolve();
    await batch;
    await settle();
    assert.deepEqual(order, ['A', 'B', 'A']);
    steps[2].resolve();
    await settle();
    steps[3].resolve();
    await settle();
    assert.deepEqual(order, ['A', 'B', 'A', 'B']);
    assert.equal(clock.pendingCount(), 0);
  });
});

interface MenuItem {
  command: string;
  when?: string;
  group?: string;
}

interface Manifest {
  contributes: {
    commands?: Array<{ command: string; icon?: string }>;
    menus?: Record<string, MenuItem[] | undefined>;
    configuration?: Array<{ properties?: Record<string, { enum?: string[]; default?: unknown }> }>;
  };
}

const readManifest = (path: string): Manifest =>
  JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8')) as Manifest;

/** The products whose server runs JVM debug sessions. */
const JVM_PRODUCTS = [
  '../../kotlin-vscode/package.json',
  '../../../intellij-vscode/intellij-server/package.json',
  '../../../intellij-vscode/intellij-server-experimental/package.json',
];

/** `check-metadata-sync.mjs` copies the command into these products too; they run no JVM debug session. */
const OTHER_PRODUCTS = [
  '../../../intellij-vscode/goland-server/package.json',
  '../../../intellij-vscode/datagrip-server/package.json',
];

describe('"Reload Changed Classes" in the product manifests', () => {
  test('a product without JVM debug sessions hides the copied command from the Command Palette', () => {
    for (const path of OTHER_PRODUCTS) {
      const item = readManifest(path).contributes.menus?.commandPalette?.find(
        (entry) => entry.command === HOT_SWAP_COMMAND,
      );
      assert.equal(item?.when, 'false', path);
    }
  });

  test('the command is contributed with an icon', () => {
    for (const path of JVM_PRODUCTS) {
      const command = readManifest(path).contributes.commands?.find(
        (entry) => entry.command === HOT_SWAP_COMMAND,
      );
      assert.ok(command?.icon, path);
    }
  });

  test('the debug toolbar shows the button for an IntelliJ session that can reload', () => {
    for (const path of JVM_PRODUCTS) {
      const item = readManifest(path).contributes.menus?.['debug/toolBar']?.find(
        (entry) => entry.command === HOT_SWAP_COMMAND,
      );
      assert.ok(item?.when?.split(' && ').includes(HOT_SWAP_SUPPORTED_CONTEXT), path);
      assert.ok(item?.when?.includes('debugType =~ /^intellij_/'), path);
      // VS Code ignores a menu key it does not know, e.g. `debug/toolbar`.
      assert.equal(readManifest(path).contributes.menus?.['debug/toolbar'], undefined, path);
    }
  });

  test('the Command Palette offers the command only while an IntelliJ session is debugged', () => {
    for (const path of JVM_PRODUCTS) {
      const item = readManifest(path).contributes.menus?.commandPalette?.find(
        (entry) => entry.command === HOT_SWAP_COMMAND,
      );
      assert.equal(item?.when, 'inDebugMode && debugType =~ /^intellij_/', path);
    }
  });

  test('the setting offers exactly the modes the code reads', () => {
    for (const path of JVM_PRODUCTS) {
      const schema = readManifest(path)
        .contributes.configuration?.map((group) => group.properties?.[HOT_SWAP_SETTING])
        .find((entry) => entry !== undefined);
      assert.deepEqual(schema?.enum, [...HOT_SWAP_MODES], path);
      assert.equal(schema.default, 'manual', path);
    }
  });
});
