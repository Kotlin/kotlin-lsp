import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { isBuildFilePath } from './buildFiles';

describe('isBuildFilePath', () => {

    test('matches by basename regardless of directory depth', () => {
        assert.equal(isBuildFilePath('/pom.xml'), true);
        assert.equal(isBuildFilePath('/a/b/c/pom.xml'), true);
        assert.equal(isBuildFilePath('/a/b/macros.bzl'), true);
    });

    const nonBuildFiles = [
        'Foo.kt',
        'README.md',
        'pom.xml.bak',
        'build.gradle.old',
        'notpom.xml',
        'bzl', // no dot, must not match the `.bzl` suffix rule
        '.bzlignore',
    ];

    for (const name of nonBuildFiles) {
        test(`does not match ${name}`, () => {
            assert.equal(isBuildFilePath(`/project/${name}`), false);
        });
    }
});
