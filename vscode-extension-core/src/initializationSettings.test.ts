import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  isSettingsDocument,
  sanitizeBoolean,
  sanitizeBuildTools,
  sanitizeConfiguredProjects,
  sanitizeOptionalString,
  settingsChangeAction,
  settingsCommand,
  settingsScope,
  shouldReportSettingsProblems,
} from './initializationSettings';

const PROJECTS = 'intellij.projects';
const SDK = 'intellij.jdkForSymbolResolution';
const BUILD_TOOL = 'intellij.buildTool';
const DISABLE_WAL = 'intellij.disableRocksDBWriteAheadLog';

const validProject = { type: 'maven', path: 'file:///proj/pom.xml' };

const problem = (setting: string, message: string, kind: 'incomplete' | 'invalid' = 'invalid') => ({
  setting,
  kind,
  message,
});

describe('settingsScope', () => {
  it('prefers folder and workspace values over user settings', () => {
    assert.equal(settingsScope({ globalValue: [], workspaceValue: [] }), 'workspace');
    assert.equal(
      settingsScope({ globalValue: [], workspaceValue: [], workspaceFolderValue: [] }),
      'workspaceFolder',
    );
  });

  it('selects the user scope when only a user value exists', () => {
    assert.equal(settingsScope({ globalValue: [] }), 'global');
  });

  it('falls back to unknown when the setting is not configured', () => {
    assert.equal(settingsScope(undefined), 'unknown');
    assert.equal(settingsScope({}), 'unknown');
  });

  it('maps each scope to its settings editor command', () => {
    assert.equal(settingsCommand('workspaceFolder'), 'workbench.action.openFolderSettings');
    assert.equal(settingsCommand('workspace'), 'workbench.action.openWorkspaceSettings');
    assert.equal(settingsCommand('global'), 'workbench.action.openSettings');
    assert.equal(settingsCommand('unknown'), 'workbench.action.openSettings');
  });
});

describe('sanitizeConfiguredProjects', () => {
  it('keeps valid entries untouched, including build-tool specific fields', () => {
    const bazel = { type: 'bazel', path: 'file:///proj', 'project-path': '.bazelproject' };
    const maven = { ...validProject, env: { M2: '/m2' }, 'java-home': '/jdk' };

    assert.deepEqual(sanitizeConfiguredProjects(PROJECTS, [bazel, maven]), {
      value: [bazel, maven],
      problems: [],
    });
  });

  it('drops the empty entry the settings editor inserts, keeping the rest', () => {
    assert.deepEqual(sanitizeConfiguredProjects(PROJECTS, [{}, validProject]), {
      value: [validProject],
      problems: [
        problem(
          PROJECTS,
          '`intellij.projects` entry #0 requires "type" to be specified as a non-empty string',
          'incomplete',
        ),
      ],
    });
  });

  it('drops entries with a blank or non-string required field', () => {
    const { value, problems } = sanitizeConfiguredProjects(PROJECTS, [
      { type: 'maven', path: '   ' },
      { type: 42, path: 'file:///proj' },
      'file:///proj',
      null,
    ]);

    assert.deepEqual(value, []);
    assert.deepEqual(problems, [
      problem(
        PROJECTS,
        '`intellij.projects` entry #0 requires "path" to be specified as a non-empty string',
        'incomplete',
      ),
      problem(
        PROJECTS,
        '`intellij.projects` entry #1 requires "type" to be specified as a non-empty string',
      ),
      problem(PROJECTS, '`intellij.projects` entry #2 must be an object'),
      problem(PROJECTS, '`intellij.projects` entry #3 must be an object'),
    ]);
  });

  it('drops entries with unsupported project types', () => {
    assert.deepEqual(
      sanitizeConfiguredProjects(PROJECTS, [
        { type: 'unknown', path: 'file:///proj' },
        { type: 'subproject', path: 'file:///proj/nested' },
      ]),
      {
        value: [{ type: 'subproject', path: 'file:///proj/nested' }],
        problems: [
          problem(PROJECTS, '`intellij.projects` entry #0 has an unsupported "type": "unknown"'),
        ],
      },
    );
  });

  it('drops entries whose optional fields have the wrong shape', () => {
    const { value, problems } = sanitizeConfiguredProjects(PROJECTS, [
      { ...validProject, 'java-home': ['/jdk'] },
      { ...validProject, env: { M2: 2 } },
      { ...validProject, 'system-properties': 'a=b' },
    ]);

    assert.deepEqual(value, []);
    assert.deepEqual(problems, [
      problem(PROJECTS, '`intellij.projects` entry #0 "java-home" must be a string'),
      problem(PROJECTS, '`intellij.projects` entry #1 "env" must be an object with string values'),
      problem(
        PROJECTS,
        '`intellij.projects` entry #2 "system-properties" must be an object with string values',
      ),
    ]);
  });

  it('reports a non-array setting instead of forwarding it', () => {
    assert.deepEqual(sanitizeConfiguredProjects(PROJECTS, validProject), {
      value: [],
      problems: [problem(PROJECTS, '`intellij.projects` must be an array')],
    });
  });

  it('treats an unset setting as no projects', () => {
    assert.deepEqual(sanitizeConfiguredProjects(PROJECTS, undefined), { value: [], problems: [] });
    assert.deepEqual(sanitizeConfiguredProjects(PROJECTS, null), { value: [], problems: [] });
  });
});

describe('shouldReportSettingsProblems', () => {
  const incomplete = problem(PROJECTS, 'incomplete project', 'incomplete');
  const invalid = problem(PROJECTS, 'invalid project');

  it('keeps incomplete graphical-settings drafts quiet', () => {
    assert.equal(
      shouldReportSettingsProblems([incomplete], { settingsDocumentSaved: false }),
      false,
    );
  });

  it('reports an incomplete draft after a settings document is saved', () => {
    assert.equal(shouldReportSettingsProblems([incomplete], { settingsDocumentSaved: true }), true);
  });

  it('reports invalid values without waiting for a save', () => {
    assert.equal(
      shouldReportSettingsProblems([incomplete, invalid], { settingsDocumentSaved: false }),
      true,
    );
  });
});

describe('sanitizeOptionalString', () => {
  it('passes a string through and treats an unset setting as absent', () => {
    assert.deepEqual(sanitizeOptionalString(SDK, '/jdk'), { value: '/jdk', problems: [] });
    assert.deepEqual(sanitizeOptionalString(SDK, undefined), { value: undefined, problems: [] });
  });

  it('drops a non-string value', () => {
    assert.deepEqual(sanitizeOptionalString(SDK, { path: '/jdk' }), {
      value: undefined,
      problems: [problem(SDK, '`intellij.jdkForSymbolResolution` must be a string')],
    });
  });
});

describe('isSettingsDocument', () => {
  it('matches the files a settings edit is saved to', () => {
    assert.equal(isSettingsDocument('/Users/me/Code/User/settings.json'), true);
    assert.equal(isSettingsDocument('/proj/.vscode/settings.json'), true);
    assert.equal(isSettingsDocument('/proj/proj.code-workspace'), true);
  });

  it('ignores other saved files', () => {
    assert.equal(isSettingsDocument('/proj/package.json'), false);
    assert.equal(isSettingsDocument('/proj/settings.json.bak'), false);
  });
});

describe('settingsChangeAction', () => {
  const change = (
    affectsLaunch: boolean,
    affectsInitializationOptions: boolean,
    serverRunning: boolean,
  ) => settingsChangeAction({ affectsLaunch, affectsInitializationOptions, serverRunning });

  it('ignores changes to settings the server never sees', () => {
    assert.equal(change(false, false, true), 'none');
    assert.equal(change(false, false, false), 'none');
  });

  it('reloads the workspace for initialization options on a running server', () => {
    assert.equal(change(false, true, true), 'reload');
  });

  it('restarts for launch settings, which only a new process reads', () => {
    assert.equal(change(true, false, true), 'restart');
    assert.equal(change(true, true, true), 'restart');
  });

  it('starts the server when it is down, whatever changed', () => {
    assert.equal(change(false, true, false), 'start');
    assert.equal(change(true, false, false), 'start');
  });
});

describe('sanitizeBoolean', () => {
  it('passes a boolean through and defaults an unset setting to false', () => {
    assert.deepEqual(sanitizeBoolean(DISABLE_WAL, true), { value: true, problems: [] });
    assert.deepEqual(sanitizeBoolean(DISABLE_WAL, undefined), { value: false, problems: [] });
  });

  it('drops a value that only looks like a boolean', () => {
    assert.deepEqual(sanitizeBoolean(DISABLE_WAL, 'true'), {
      value: false,
      problems: [problem(DISABLE_WAL, '`intellij.disableRocksDBWriteAheadLog` must be a boolean')],
    });
  });
});

describe('sanitizeBuildTools', () => {
  it('keeps string overrides and leaves out folders without one', () => {
    assert.deepEqual(
      sanitizeBuildTools(BUILD_TOOL, [
        ['file:///a', 'gradle'],
        ['file:///b', undefined],
      ]),
      { value: { 'file:///a': 'gradle' }, problems: [] },
    );
  });

  it('drops a non-string override without losing the other folders', () => {
    const { value, problems } = sanitizeBuildTools(BUILD_TOOL, [
      ['file:///a', 42],
      ['file:///b', 'maven'],
    ]);

    assert.deepEqual(value, { 'file:///b': 'maven' });
    assert.deepEqual(problems, [
      problem(BUILD_TOOL, '`intellij.buildTool` for file:///a must be a string'),
    ]);
  });
});
