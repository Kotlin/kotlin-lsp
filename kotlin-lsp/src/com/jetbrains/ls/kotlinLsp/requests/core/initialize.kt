// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.analyzer.bootstrap.AnalyzerContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.workspaceFolderPaths
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.codeActions.LSCodeActions
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokens
import com.jetbrains.ls.imports.api.EmptyWorkspaceImporter
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.api.applyChangesWithDeduplication
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.ls.kotlinLsp.util.sendSystemInfoToClient
import com.jetbrains.ls.snapshot.api.impl.core.InitializeParamsEntity
import com.jetbrains.ls.snapshot.api.impl.core.lspInitializationOptions
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.implementation.ProgressReporter
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.withProgress
import com.jetbrains.lsp.protocol.CodeActionRegistrationOptions
import com.jetbrains.lsp.protocol.CompletionRegistrationOptions
import com.jetbrains.lsp.protocol.DiagnosticOptions
import com.jetbrains.lsp.protocol.ExecuteCommandOptions
import com.jetbrains.lsp.protocol.FileOperationFilter
import com.jetbrains.lsp.protocol.FileOperationPattern
import com.jetbrains.lsp.protocol.FileOperationRegistrationOptions
import com.jetbrains.lsp.protocol.FileOperations
import com.jetbrains.lsp.protocol.Initialize
import com.jetbrains.lsp.protocol.InitializeParams
import com.jetbrains.lsp.protocol.InitializeResult
import com.jetbrains.lsp.protocol.InlayHintRegistrationOptions
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.LogMessageNotificationType
import com.jetbrains.lsp.protocol.LogMessageParams
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.OrBoolean
import com.jetbrains.lsp.protocol.SemanticTokensLegend
import com.jetbrains.lsp.protocol.SemanticTokensRegistrationOptions
import com.jetbrains.lsp.protocol.ServerCapabilities
import com.jetbrains.lsp.protocol.ServerWorkspaceCapabilities
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import com.jetbrains.lsp.protocol.SignatureHelpRegistrationOptions
import com.jetbrains.lsp.protocol.TextDocumentSyncKind
import com.jetbrains.lsp.protocol.WorkDoneProgress
import com.jetbrains.lsp.protocol.WorkDoneProgress.Report
import com.jetbrains.lsp.protocol.WorkspaceFoldersServerCapabilities
import com.jetbrains.lsp.protocol.WorkspaceSymbolRegistrationOptions
import fleet.kernel.change
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

private val LOG: Logger by lazy { fileLogger() }

context(_: LSServer, configuration: LSConfiguration)
internal fun LspHandlersBuilder.initializeRequest(workspaceImporters: List<WorkspaceImporter>) {
    request(Initialize) { initParams ->
        Client.update { it.copy(trace = initParams.trace) }
        // TODO: LSP-223 take into account client capabilities

        lspClient.sendRunConfigurationInfoToClient()
        lspClient.sendSystemInfoToClient()

        LOG.info(
            "Got `initialize` request from ${initParams.clientInfo ?: "unknown"}\nparams:\n${
                LSP.json.encodeToString(
                    serializer = InitializeParams.serializer(),
                    value = initParams,
                )
            }"
        )

        val folders = workspaceFolderPaths(initParams)

        change {
            InitializeParamsEntity.new(initParams)
        }

        if (initParams.lspInitializationOptions?.skipImport != true) {
            indexFolders(folders, workspaceImporters, initParams)
        }

        // TODO: LSP-226 determine capabilities based on registered configuration entries (LSConfigurationEntry)
        // TODO: LSP-227 register capabilities dynamically for each language separately
        val result = InitializeResult(
            capabilities = ServerCapabilities(
                textDocumentSync = TextDocumentSyncKind.Incremental,
                definitionProvider = OrBoolean(true),
                implementationProvider = OrBoolean(true),
                typeDefinitionProvider = OrBoolean(true),
                diagnosticProvider = DiagnosticOptions(
                    identifier = null,
                    interFileDependencies = true,
                    workspaceDiagnostics = false,
                    workDoneProgress = false,
                ),
                semanticTokensProvider = SemanticTokensRegistrationOptions(
                    legend = run {
                        val registry = LSSemanticTokens.createRegistry()
                        SemanticTokensLegend(
                            tokenTypes = registry.types.map { it.name },
                            tokenModifiers = registry.modifiers.map { it.name },
                        )
                    },
                    range = OrBoolean(true),
                    full = OrBoolean(true),
                ),
                completionProvider = CompletionRegistrationOptions(
                    documentSelector = null,
                    triggerCharacters = listOf("."), // TODO: LSP-226 should be customized
                    allCommitCharacters = null,
                    resolveProvider = configuration.entries<LSCompletionProvider>().any { it.supportsResolveRequest },
                    completionItem = null,
                ),
                codeActionProvider = OrBoolean.of(
                    CodeActionRegistrationOptions(
                        codeActionKinds = LSCodeActions.supportedCodeActionKinds(),
                        resolveProvider = false,
                        workDoneProgress = false,
                    )
                ),
                executeCommandProvider = ExecuteCommandOptions(
                    commands = configuration.allCommandDescriptors.map { it.name },
                ),
                referencesProvider = OrBoolean(true),
                hoverProvider = OrBoolean(true),
                documentSymbolProvider = OrBoolean(true),
                workspaceSymbolProvider = OrBoolean.of(
                    WorkspaceSymbolRegistrationOptions(resolveProvider = false, workDoneProgress = true)
                ),
                workspace = ServerWorkspaceCapabilities(
                    workspaceFolders = WorkspaceFoldersServerCapabilities(
                        supported = true,
                        changeNotifications = JsonPrimitive(true),
                    ),
                    fileOperations = FileOperations(
                        willRename = FileOperationRegistrationOptions(
                            filters = listOf(
                                FileOperationFilter(
                                    pattern = FileOperationPattern(
                                        "**/*"
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                renameProvider = OrBoolean(true),
                signatureHelpProvider = SignatureHelpRegistrationOptions(
                    triggerCharacters = listOf("(", ","),
                    retriggerCharacters = listOf(","),
                    workDoneProgress = false,
                ),
                documentFormattingProvider = OrBoolean(true),
                inlayHintProvider = OrBoolean.of(
                    InlayHintRegistrationOptions(resolveProvider = true)
                ),
                typeHierarchyProvider = OrBoolean(true),
            ),
            serverInfo = InitializeResult.ServerInfo(
                name = "Kotlin LSP by JetBrains",
                version = "0.1", // TODO: LSP-225 proper version here from the build number
            ),
        )
        LOG.info("InitializeResult:\n${LSP.json.encodeToString(InitializeResult.serializer(), result)}")
        result
    }
}

private suspend fun LspClient.sendRunConfigurationInfoToClient() {
    val client = Client.current ?: return
    val runConfig = client.runConfig
    notify(
        notificationType = LogMessageNotificationType,
        params = LogMessageParams(MessageType.Info, "Process stared with\n${runConfig}"),
    )
}

context(server: LSServer, handlerContext: LspHandlerContext)
private suspend fun indexFolders(
    folders: List<Path>,
    workspaceImporters: List<WorkspaceImporter>,
    params: InitializeParams,
) {
    val defaultSdkPath = defaultSdkPath(params.initializationOptions)
    lspClient.withProgress(params, beginTitle = "Initializing project") { progress ->
        val emptyWorkspaceImporters = workspaceImporters.asSequence()
            .filterIsInstance<EmptyWorkspaceImporter>()
        server.workspaceStructure.updateWorkspaceModelDirectly { virtualFileUrlManager, storage ->
            if (folders.isNotEmpty()) {
                for (folder in folders) {
                    initFolder(folder, workspaceImporters, progress, defaultSdkPath, virtualFileUrlManager, storage)
                }
            } else {
                val emptyStorage = emptyWorkspaceImporters.map { importer ->
                    importer.createEmptyWorkspace(defaultSdkPath, virtualFileUrlManager)
                }.firstOrNull()
                if (emptyStorage != null) {
                    progress.report(Report(message = "Using light mode"))
                    storage.applyChangesWithDeduplication(emptyStorage)
                } else {
                    progress.report(Report(message = "Skipping import (light mode not supported)"))
                }
            }
            progress.report(Report(message = "Indexing..."))
        }

        WorkDoneProgress.End(message = "Workspace is imported and indexed")
    }
}

context(handlerContext: LspHandlerContext)
private suspend fun initFolder(
    folder: Path,
    workspaceImporters: List<WorkspaceImporter>,
    progress: ProgressReporter,
    defaultSdkPath: Path?,
    virtualFileUrlManager: VirtualFileUrlManager,
    storage: MutableEntityStorage,
) {
    val project = AnalyzerContext.current
    progress.report(Report(message = "Importing folder $folder"))
    for (importer in workspaceImporters) {
        val unresolved = mutableSetOf<String>()
        LOG.info("Trying to import using ${importer.javaClass.simpleName}")
        val imported = try {
            withContext(Dispatchers.IO) {
                importer.importWorkspace(project.project, folder, defaultSdkPath, virtualFileUrlManager, unresolved::add)
                    ?.let { diff ->
                        storage.applyChangesWithDeduplication(diff)
                        true
                    } ?: false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = (e as? WorkspaceImportException)?.message ?: "Error importing folder"
            val logMessage = (e as? WorkspaceImportException)?.logMessage ?: "Error importing folder:\n${e.stackTraceToString()}"

            lspClient.notify(
                notificationType = ShowMessageNotificationType,
                params = ShowMessageParams(MessageType.Error, "$message Check the log for details."),
            )

            lspClient.notify(
                notificationType = LogMessageNotificationType,
                params = LogMessageParams(MessageType.Error, logMessage),
            )
            LOG.error(e)
            false
        }

        if (imported) {
            progress.report(Report(message = "Successfully imported folder $folder"))
            if (unresolved.isNotEmpty()) {
                lspClient.notify(
                    notificationType = ShowMessageNotificationType,
                    params = ShowMessageParams(MessageType.Warning, unresolved.joinToString(", ", "Couldn't resolve some dependencies: ")),
                )
            }
            break
        }
    }
}

private fun defaultSdkPath(initOptions: JsonElement?): Path? = initOptions
    ?.let { it as? JsonObject }
    ?.get("defaultJdk")
    ?.let { it as? JsonPrimitive }
    ?.takeIf { it.isString }
    ?.content
    ?.takeIf { it.isNotBlank() }
    ?.let { return Path.of(it) }
