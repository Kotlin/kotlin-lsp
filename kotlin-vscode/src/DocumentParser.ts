import * as fs from 'node:fs';
import * as path from 'node:path';
import {ExtensionContext, TextDocument, Uri, workspace} from 'vscode';
import {Language, Parser, Tree, Edit} from 'web-tree-sitter';

export class DocumentParser {
    private readonly context: ExtensionContext;
    readonly languageId: string;
    private parser: Parser | null = null;
    private readonly treeCache = new Map<string, Tree|null>();

    private static isTreeSitterInitialized = false;

    static async create(context: ExtensionContext, languageId: string): Promise<DocumentParser> {
        const parser = new DocumentParser(context, languageId);
        await parser.init();
        return parser;
    }

    private constructor(context: ExtensionContext, languageId: string) {
        this.context = context;
        this.languageId = languageId;
    }

    private async init() {
        if (!DocumentParser.isTreeSitterInitialized) {
            const locateFile = () => DocumentParser.resolveParserWasmPath(this.context.extensionUri);
            await Parser.init({ locateFile });
            DocumentParser.isTreeSitterInitialized = true;
        }

        const grammarWasmPath = this.resolveGrammarWasmPath(this.languageId);
        const language = await Language.load(grammarWasmPath);
        this.parser = new Parser();
        this.parser.setLanguage(language);
        this.context.subscriptions.push({
            dispose: () => {
                this.parser?.delete();
            }
        });

        this.context.subscriptions.push({
            dispose: () => {
                for (const tree of this.treeCache.values()) {
                    tree?.delete();
                }
            }
        });

        this.context.subscriptions.push(workspace.onDidChangeTextDocument((event) => {
            const document = event.document;
            if (document.languageId !== this.languageId) return;

            const oldTree = this.treeCache.get(document.uri.toString()) ?? null;

            if (oldTree) {
                for (const change of event.contentChanges) {
                    const startPosition = {
                        row: change.range.start.line,
                        column: change.range.start.character,
                    };
                    const oldEndPosition = {
                        row: change.range.end.line,
                        column: change.range.end.character,
                    };
                    const lines = change.text.split('\n');
                    const newEndPosition = {
                        row: startPosition.row + lines.length - 1,
                        column: lines.length === 1
                                ? startPosition.column + lines[0].length
                                : lines[lines.length - 1].length,
                    };

                    oldTree.edit(new Edit({
                        startIndex: change.rangeOffset,
                        oldEndIndex: change.rangeOffset + change.rangeLength,
                        newEndIndex: change.rangeOffset + change.text.length,
                        startPosition,
                        oldEndPosition,
                        newEndPosition,
                    }));
                }
            }

            this.parser!.reset();
            const newTree = this.parser!.parse(document.getText(), oldTree);
            oldTree?.delete();
            this.treeCache.set(document.uri.toString(), newTree);
        }));

       this.context.subscriptions.push(
                workspace.onDidCloseTextDocument(event => {
                    const uri = event.uri.toString();
                    this.treeCache.get(uri)?.delete();
                    this.treeCache.delete(uri);
                })
        );
    }

    parseDocument(document: TextDocument): Tree | null {
        const uri = document.uri.toString();
        let cachedTree = this.treeCache.get(uri);
        if (cachedTree === undefined) {
            this.parser!.reset();
            cachedTree = this.parser!.parse(document.getText());
            this.treeCache.set(document.uri.toString(), cachedTree);
        }
        return cachedTree;
    }

    private static resolveParserWasmPath(extensionUri: Uri): string {
        const bundledPath = Uri.joinPath(
                extensionUri,
                'grammars',
                'web-tree-sitter.wasm'
        ).fsPath;

        if (fs.existsSync(bundledPath)) {
            return bundledPath;
        }

        const nodeModulesPath = Uri.joinPath(
                extensionUri,
                'node_modules',
                'web-tree-sitter',
                'web-tree-sitter.wasm'
        ).fsPath;

        if (fs.existsSync(nodeModulesPath)) {
            return nodeModulesPath;
        }

        throw Error('Couldn\'t find web-tree-sitter.wasm');
    }

    private resolveGrammarWasmPath(languageId: string): string {
        const bundledPath = path.join(
                this.context.extensionPath,
                'grammars',
                `tree-sitter-${languageId}.wasm`
        );

        if (fs.existsSync(bundledPath)) {
            return bundledPath;
        }

        const scopedPath = path.join(
                this.context.extensionPath,
                'node_modules',
                '@tree-sitter-grammars',
                `tree-sitter-${languageId}`,
                `tree-sitter-${languageId}.wasm`
        );

        if (fs.existsSync(scopedPath)) {
            return scopedPath;
        }

        const unscopedPath = path.join(
                this.context.extensionPath,
                'node_modules',
                `tree-sitter-${languageId}`,
                `tree-sitter-${languageId}.wasm`
        );

        if (fs.existsSync(unscopedPath)) {
            return unscopedPath;
        }

        throw Error(`Couldn't find parser grammar for ${languageId}`);
    }
}
