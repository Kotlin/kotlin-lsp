import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { lensLaunchConfig } from './lensLaunchModel';

const typeByTool = { gradle: 'intellij_gradle', bazel: 'intellij_bazel' };
const jvmType = 'intellij_jvm';
const toPath = (uri: string) => uri.replace('file://', '');

describe('lensLaunchConfig', () => {
  test('a tool with a configuration type launches through it and names the tool', () => {
    assert.deepEqual(
      lensLaunchConfig({ mainClass: 'com.acme.Main', uri: 'file:///w/Main.kt', tool: 'gradle' }, typeByTool, jvmType, toPath),
      { type: 'intellij_gradle', request: 'launch', name: 'Main', mainClass: 'com.acme.Main', file: '/w/Main.kt', tool: 'gradle' },
    );
  });

  test('a tool without a configuration type launches as a JVM and still names the tool', () => {
    const config = lensLaunchConfig({ mainClass: 'Main', uri: 'file:///w/Main.java', tool: 'maven' }, typeByTool, jvmType, toPath);
    assert.equal(config.type, jvmType);
    assert.equal(config.tool, 'maven');
  });

  test('no tool means a JVM launch that leaves the choice to the server', () => {
    const config = lensLaunchConfig({ mainClass: 'Main' }, typeByTool, jvmType, toPath);
    assert.equal(config.type, jvmType);
    assert.equal(config.tool, undefined);
    assert.equal(config.file, undefined);
  });
});
