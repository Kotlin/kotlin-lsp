import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { isWorkspaceSettingsPath } from './workspaceSettingsFile';

describe('isWorkspaceSettingsPath', () => {
  test('matches .vscode/settings.json at any depth', () => {
    assert.equal(isWorkspaceSettingsPath('/project/.vscode/settings.json'), true);
    assert.equal(isWorkspaceSettingsPath('/a/b/subproject/.vscode/settings.json'), true);
  });

  const nonSettingsFiles = [
    '/project/settings.json', // not inside .vscode
    '/project/.vscode/launch.json',
    '/project/.vscode/settings.json.bak',
    '/project/vscode/settings.json',
    '/project/pom.xml', // build files reload server-side, not here
    '/project/BUILD.bazel',
  ];

  for (const fsPath of nonSettingsFiles) {
    test(`does not match ${fsPath}`, () => {
      assert.equal(isWorkspaceSettingsPath(fsPath), false);
    });
  }
});
