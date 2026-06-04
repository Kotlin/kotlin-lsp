import path from 'node:path';
import { rspack, type Configuration } from '@rspack/core';
import { TsCheckerRspackPlugin } from 'ts-checker-rspack-plugin';

export interface PackageCopy {
    from: string;
    to: string;
}

export function copyToPackage(packageDir: string, from: string, to: string): PackageCopy {
    return {
        from,
        to: path.resolve(packageDir, to),
    };
}

export function dependencyWasm(
    packageDir: string,
    packageName: string,
    wasmName: string,
): PackageCopy {
    return copyToPackage(
        packageDir,
        path.resolve(packageDir, 'node_modules', packageName, wasmName),
        path.join('grammars', wasmName),
    );
}

function resolvePackagePath(packageDir: string, resource: string): string {
    return path.isAbsolute(resource) ? resource : path.resolve(packageDir, resource);
}

export function createExtensionConfig(
    packageDir: string,
    copies: PackageCopy[],
    entry = './src/extension.ts',
    sourceDirs = [path.resolve(packageDir, 'src')],
    tsconfig = path.join(packageDir, 'tsconfig.json'),
): Configuration {
    const outputPath = path.resolve(packageDir, 'out/dist');

    return {
        target: 'node',
        entry: resolvePackagePath(packageDir, entry),
        output: {
            path: outputPath,
            filename: 'extension.js',
            library: {
                type: 'modern-module',
            },
            devtoolModuleFilenameTemplate: '../../[resource-path]',
        },
        devtool: 'source-map',
        externalsType: 'module-import',
        externals: {
            vscode: 'vscode',
        },
        resolve: {
            extensions: ['.ts', '.js'],
            alias: {
                'web-tree-sitter$': path.resolve(
                    packageDir,
                    'node_modules/web-tree-sitter/web-tree-sitter.cjs',
                ),
                'vscode-languageserver-types$': path.resolve(
                    packageDir,
                    'node_modules/vscode-languageserver-types/lib/esm/main.js',
                ),
            },
        },
        module: {
            rules: [
                {
                    test: /\.ts$/,
                    include: sourceDirs,
                    loader: 'builtin:swc-loader',
                    options: {
                        jsc: {
                            parser: {
                                syntax: 'typescript',
                            },
                            target: 'es2022',
                        },
                    },
                },
            ],
        },
        optimization: {
            avoidEntryIife: true,
        },
        plugins: [
            new TsCheckerRspackPlugin({
                typescript: {
                    configFile: tsconfig,
                    configOverwrite: {
                        exclude: [
                            '**/node_modules',
                            '**/.*/',
                            '**/out',
                            '**/*.test.ts',
                            '**/rspack.config.ts',
                        ],
                    },
                },
            }),
            new rspack.CopyRspackPlugin({
                patterns: copies.map(({ from, to }) => ({
                    from,
                    to: path.relative(outputPath, to),
                })),
            }),
        ],
    };
}
