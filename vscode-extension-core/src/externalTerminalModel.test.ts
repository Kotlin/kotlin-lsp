import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { type EventEmitter, once } from 'node:events';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { connect as connectSocket, createServer, type Socket } from 'node:net';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { describe, test } from 'node:test';
import {
  macCommandFile,
  RELAY_SCRIPT,
  RelayStream,
  relaySocketPath,
  serialized,
  terminalSpawn,
} from './externalTerminalModel';

/** Waits for the event [name] of [emitter], at most 5 seconds: a broken stream fails the test, it does not hang. */
const event = (emitter: EventEmitter, name: string) =>
  once(emitter, name, { signal: AbortSignal.timeout(5_000) });

const relay = {
  nodePath: '/vsc/Code Helper',
  relayPath: '/tmp/r/relay.cjs',
  socketPath: '/tmp/s.sock',
};

describe('terminalSpawn', () => {
  test('macOS opens the command file in the terminal app', () => {
    const spawned = terminalSpawn({
      platform: 'darwin',
      exec: 'iTerm.app',
      title: 'Run',
      relay,
      commandFile: '/tmp/r/Run.command',
    });
    assert.deepEqual(spawned, { command: 'open', args: ['-a', 'iTerm.app', '/tmp/r/Run.command'] });
  });

  test('Windows PowerShell gets the relay as an encoded call, so no shell quoting applies', () => {
    const exec = 'C:\\Program Files\\PowerShell\\7\\pwsh.exe';
    const nodePath = "C:\\Users\\O'Neil\\AppData\\Local\\Programs\\Microsoft VS Code\\Code.exe";
    const spawned = terminalSpawn({
      platform: 'win32',
      exec,
      title: 'Run',
      relay: { ...relay, nodePath },
      commandFile: '',
    });
    const [encodedFlag, encoded = ''] = spawned.args.slice(-2);
    assert.deepEqual(spawned.args.slice(0, -2), [
      '/c',
      'start',
      '"Run"',
      `"${exec}"`,
      '-NoProfile',
    ]);
    assert.equal(encodedFlag, '-EncodedCommand');
    // Base64 has no character that cmd.exe or PowerShell parse.
    assert.match(encoded, /^[A-Za-z0-9+/=]+$/);
    assert.equal(
      Buffer.from(encoded, 'base64').toString('utf16le'),
      "& 'C:\\Users\\O''Neil\\AppData\\Local\\Programs\\Microsoft VS Code\\Code.exe' '/tmp/r/relay.cjs' '/tmp/s.sock'",
    );
  });

  test('Windows cmd.exe starts a titled window with the terminal and the relay quoted', () => {
    const exec = 'C:\\Windows\\System32\\cmd.exe';
    const spawned = terminalSpawn({
      platform: 'win32',
      exec,
      title: 'My "Run"',
      relay,
      commandFile: '',
    });
    assert.deepEqual(spawned, {
      command: 'cmd.exe',
      args: [
        '/c',
        'start',
        '"My Run"',
        `"${exec}"`,
        '/c',
        '""/vsc/Code Helper" "/tmp/r/relay.cjs" "/tmp/s.sock""',
      ],
      windowsVerbatimArguments: true,
    });
  });

  test('Linux passes the relay after -e, and after -x for gnome-terminal', () => {
    const args = ['/vsc/Code Helper', '/tmp/r/relay.cjs', '/tmp/s.sock'];
    assert.deepEqual(
      terminalSpawn({ platform: 'linux', exec: 'xterm', title: 'Run', relay, commandFile: '' }),
      {
        command: 'xterm',
        args: ['-e', ...args],
      },
    );
    assert.deepEqual(
      terminalSpawn({
        platform: 'linux',
        exec: '/usr/bin/gnome-terminal',
        title: 'Run',
        relay,
        commandFile: '',
      }).args,
      ['-x', ...args],
    );
  });
});

test('a serialized sender starts each send after the previous one settled, also after a failure', async () => {
  const events: string[] = [];
  const errors: unknown[] = [];
  const send = serialized(
    async (text: string) => {
      events.push(`start ${text}`);
      // The first send is the slowest: without the queue, the later ones would end first.
      await new Promise((resolve) => setTimeout(resolve, text === 'a' ? 30 : 1));
      events.push(`end ${text}`);
      if (text === 'b') throw new Error('lost');
    },
    (e) => errors.push(e),
  );
  ['a', 'b', 'c'].forEach(send);
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.deepEqual(events, ['start a', 'end a', 'start b', 'end b', 'start c', 'end c']);
  assert.equal(errors.length, 1);
});

test('the macOS command file sets the Node mode and quotes every path', () => {
  assert.equal(
    macCommandFile({ ...relay, socketPath: "/tmp/it's.sock" }),
    `#!/bin/sh\nELECTRON_RUN_AS_NODE=1 exec '/vsc/Code Helper' '/tmp/r/relay.cjs' '/tmp/it'\\''s.sock'\n`,
  );
});

test('the socket is a named pipe on Windows and a file in the temp directory elsewhere', () => {
  assert.equal(
    relaySocketPath({ platform: 'win32', tmpDir: 'C:\\t', id: 'a1' }),
    '\\\\.\\pipe\\intellij-run-a1',
  );
  assert.equal(
    relaySocketPath({ platform: 'darwin', tmpDir: '/tmp', id: 'a1' }),
    path.join('/tmp', 'intellij-run-a1.sock'),
  );
});

test(
  'the relay prints what the socket sends, forwards typed lines, and waits for Enter after the end',
  { skip: process.platform === 'win32' },
  async () => {
    const dir = await mkdtemp(path.join(tmpdir(), 'relay-test-'));
    try {
      const relayPath = path.join(dir, 'relay.cjs');
      await writeFile(relayPath, RELAY_SCRIPT);
      const socketPath = relaySocketPath({ platform: process.platform, tmpDir: dir, id: 't' });
      const server = createServer();
      server.listen(socketPath);
      await event(server, 'listening');
      const child = spawn(process.execPath, [relayPath, socketPath]);
      let stdout = '';
      child.stdout.setEncoding('utf8').on('data', (text: string) => (stdout += text));
      const [socket] = (await event(server, 'connection')) as [Socket];

      socket.write('hello\r\n');
      child.stdin.write('typed\n');
      const [typed] = (await event(socket, 'data')) as [Buffer];
      assert.equal(typed.toString(), 'typed\n');

      socket.end();
      while (!stdout.includes('Press Enter')) await event(child.stdout, 'data');
      assert.ok(stdout.startsWith('hello\r\n'), stdout);
      child.stdin.write('\n');
      const [code] = (await event(child, 'exit')) as [number];
      assert.equal(code, 0);
      server.close();
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  },
);

describe('RelayStream', () => {
  /** A stream with a recorded session, and a function that connects a relay socket to it. */
  async function relayStream() {
    const dir = await mkdtemp(path.join(tmpdir(), 'relay-stream-'));
    const input: string[] = [];
    let stops = 0;
    const stream = new RelayStream({ sendInput: (text) => input.push(text), stop: () => stops++ });
    const sockets: Socket[] = [];
    const server = createServer();
    server.listen(relaySocketPath({ platform: process.platform, tmpDir: dir, id: 's' }));
    await event(server, 'listening');
    const connect = async () => {
      const relay = connectSocket(server.address() as string);
      relay.setEncoding('utf8');
      let received = '';
      relay.on('data', (text: string) => (received += text));
      const [socket] = (await event(server, 'connection')) as [Socket];
      sockets.push(relay, socket);
      stream.connect(socket);
      return { relay, socket, received: () => received };
    };
    const cleanup = async () => {
      sockets.forEach((socket) => socket.destroy());
      server.close();
      await rm(dir, { recursive: true, force: true });
    };
    return { stream, input, stops: () => stops, connect, cleanup };
  }

  test('the output written before the relay connects arrives first, in order', async () => {
    const { stream, connect, cleanup } = await relayStream();
    try {
      stream.write('one\r\n');
      stream.write('two\r\n');
      const { relay, received } = await connect();
      stream.write('three\r\n');
      while (!received().includes('three')) await event(relay, 'data');
      assert.equal(received(), 'one\r\ntwo\r\nthree\r\n');
      relay.destroy();
    } finally {
      await cleanup();
    }
  });

  test('the text of the relay goes to the program', async () => {
    const { stream, input, connect, cleanup } = await relayStream();
    try {
      const { relay, socket } = await connect();
      relay.write('typed\n');
      await event(socket, 'data');
      assert.deepEqual(input, ['typed\n']);
      stream.end();
    } finally {
      await cleanup();
    }
  });

  test('a relay that closes before the end stops the session: the user closed the window', async () => {
    const { stops, connect, cleanup } = await relayStream();
    try {
      const { relay, socket } = await connect();
      relay.destroy();
      await event(socket, 'close');
      assert.equal(stops(), 1);
    } finally {
      await cleanup();
    }
  });

  test('the end closes the relay without a stop, and later output is dropped', async () => {
    const { stream, stops, connect, cleanup } = await relayStream();
    try {
      const { relay, socket, received } = await connect();
      stream.write('last\r\n');
      stream.end();
      stream.write('late\r\n');
      await event(relay, 'end');
      relay.end();
      await event(socket, 'close');
      assert.equal(received(), 'last\r\n');
      assert.equal(stops(), 0);
    } finally {
      await cleanup();
    }
  });

  test('a relay that connects after the end still gets the buffered output, then the end', async () => {
    const { stream, connect, cleanup } = await relayStream();
    try {
      stream.write('fast program\r\n');
      stream.end();
      const { relay, received } = await connect();
      await event(relay, 'end');
      assert.equal(received(), 'fast program\r\n');
      relay.destroy();
    } finally {
      await cleanup();
    }
  });

  test('after a failure, the buffer is dropped and no output is kept', async () => {
    const { stream, connect, cleanup } = await relayStream();
    try {
      stream.write('before\r\n');
      stream.fail();
      stream.write('after\r\n');
      const { relay, received } = await connect();
      await event(relay, 'end');
      assert.equal(received(), '');
      relay.destroy();
    } finally {
      await cleanup();
    }
  });
});
