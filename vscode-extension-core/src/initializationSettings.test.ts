import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  sanitizeBoolean,
  sanitizeBuildTools,
  sanitizeConfiguredProjects,
  sanitizeOptionalString,
} from './initializationSettings';

const PROJECTS = 'intellij.projects';
const SDK = 'intellij.jdkForSymbolResolution';
const BUILD_TOOL = 'intellij.buildTool';
const DISABLE_WAL = 'intellij.disableRocksDBWriteAheadLog';

const validProject = { type: 'maven', path: 'file:///proj/pom.xml' };

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
      problems: ['`intellij.projects` entry #0 requires a non-empty "type"'],
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
      '`intellij.projects` entry #0 requires a non-empty "path"',
      '`intellij.projects` entry #1 requires a non-empty "type"',
      '`intellij.projects` entry #2 must be an object',
      '`intellij.projects` entry #3 must be an object',
    ]);
  });

  it('drops entries whose optional fields have the wrong shape', () => {
    const { value, problems } = sanitizeConfiguredProjects(PROJECTS, [
      { ...validProject, 'java-home': ['/jdk'] },
      { ...validProject, env: { M2: 2 } },
      { ...validProject, 'system-properties': 'a=b' },
    ]);

    assert.deepEqual(value, []);
    assert.deepEqual(problems, [
      '`intellij.projects` entry #0 "java-home" must be a string',
      '`intellij.projects` entry #1 "env" must be an object with string values',
      '`intellij.projects` entry #2 "system-properties" must be an object with string values',
    ]);
  });

  it('reports a non-array setting instead of forwarding it', () => {
    assert.deepEqual(sanitizeConfiguredProjects(PROJECTS, validProject), {
      value: [],
      problems: ['`intellij.projects` must be an array'],
    });
  });

  it('treats an unset setting as no projects', () => {
    assert.deepEqual(sanitizeConfiguredProjects(PROJECTS, undefined), { value: [], problems: [] });
    assert.deepEqual(sanitizeConfiguredProjects(PROJECTS, null), { value: [], problems: [] });
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
      problems: ['`intellij.jdkForSymbolResolution` must be a string'],
    });
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
      problems: ['`intellij.disableRocksDBWriteAheadLog` must be a boolean'],
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
    assert.deepEqual(problems, ['`intellij.buildTool` for file:///a must be a string']);
  });
});
