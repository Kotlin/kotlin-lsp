import * as path from 'node:path';

const BUILD_FILE_NAMES = new Set([
    'pom.xml',
    'build.gradle',
    'build.gradle.kts',
    'settings.gradle',
    'settings.gradle.kts',
    'BUILD',
    'BUILD.bazel',
    'MODULE.bazel',
    'WORKSPACE',
    'WORKSPACE.bazel',
    '.bazelproject',
]);

export function isBuildFilePath(fsPath: string): boolean {
    const name = path.basename(fsPath);
    return BUILD_FILE_NAMES.has(name) || name.endsWith('.bzl');
}
