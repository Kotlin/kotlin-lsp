// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.jarLibraries
import com.jetbrains.ls.api.core.util.toLspUri
import com.jetbrains.ls.api.core.util.updateWorkspaceModel
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.allCommandDescriptors
import com.jetbrains.ls.api.features.codeActions.LSCodeActions
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.entries
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokens
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.jps.JpsWorkspaceImporter
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.ls.kotlinLsp.util.importProject
import com.jetbrains.ls.kotlinLsp.util.registerStdlibAndJdk
import com.jetbrains.ls.kotlinLsp.util.sendSystemInfoToClient
import com.jetbrains.ls.snapshot.api.impl.core.InitializeParamsEntity
import com.jetbrains.lsp.implementation.*
import com.jetbrains.lsp.protocol.*
import fleet.kernel.change
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.*

context(_: LSServer, _: LSConfiguration)
internal fun LspHandlersBuilder.initializeRequest() {
    request(Initialize) { initParams ->
        Client.update { it.copy(trace = initParams.trace) }
        // TODO take into account client capabilities LSP-223

        lspClient.sendRunConfigurationInfoToClient()
        lspClient.sendSystemInfoToClient()

        LOG.info("Got `initialize` request from ${initParams.clientInfo ?: "unknown"}\nparams:\n${LSP.json.encodeToString(InitializeParams.serializer(), initParams)}")

        val rootUri = initParams.rootUri
        val rootPath = initParams.rootPath
        val workspaceFolders = initParams.workspaceFolders
        val folders = when {
            workspaceFolders != null -> workspaceFolders
            rootUri != null -> listOf(WorkspaceFolder(rootUri.uri, rootUri.uri.uri))
            rootPath != null -> {
                val path = Path(rootPath)
                listOf(WorkspaceFolder(path.toLspUri(), path.name))
            }

            else -> emptyList()
        }

        change {
            InitializeParamsEntity.single().initializeParams.complete(initParams)
        }

        indexFolders(folders, initParams)

        // TODO LSP-226 determine base on entries,
        // TODO LSP-227 register capabilities dynamically for each language separately
        val result = InitializeResult(
            capabilities = ServerCapabilities(
                textDocumentSync = TextDocumentSyncKind.Incremental,
                definitionProvider = OrBoolean(true),
                diagnosticProvider = DiagnosticOptions(
                    identifier = null,
                    interFileDependencies = true,
                    workspaceDiagnostics = false,
                    workDoneProgress = false,
                ),
                semanticTokensProvider = SemanticTokensOptions(
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
                    triggerCharacters = listOf("."), // TODO LSP-226 should be customized
                    allCommitCharacters = null,
                    resolveProvider = entries<LSCompletionProvider>().any { it.supportsResolveRequest },
                    completionItem = null,
                ),
                codeActionProvider = OrBoolean.of(
                    CodeActionOptions.serializer(),
                    CodeActionOptions(
                        codeActionKinds = LSCodeActions.supportedCodeActionKinds(),
                        resolveProvider = false,
                        workDoneProgress = false,
                    )
                ),
                executeCommandProvider = ExecuteCommandOptions(
                    commands = allCommandDescriptors.map { it.name }
                ),
                referencesProvider = OrBoolean(true),
                hoverProvider = OrBoolean(true),
                documentSymbolProvider = OrBoolean(true),
                workspaceSymbolProvider = OrBoolean.of(WorkspaceSymbolOptions.serializer(), WorkspaceSymbolOptions(resolveProvider = false, workDoneProgress = true)),
                workspace = ServerWorkspaceCapabilities(
                    workspaceFolders = WorkspaceFoldersServerCapabilities(
                        supported = true,
                        changeNotifications = JsonPrimitive(true),
                    )
                ),
                renameProvider = OrBoolean(true),
                signatureHelpProvider = SignatureHelpOptions(
                    triggerCharacters = listOf("(", ","),
                    retriggerCharacters = listOf(","),
                    workDoneProgress = false
                ),
                documentFormattingProvider = OrBoolean(true),
                inlayHintProvider = OrBoolean.of(InlayHintRegistrationOptions.serializer(), InlayHintRegistrationOptions(resolveProvider = true)),
            ),
            serverInfo = InitializeResult.ServerInfo(
                name = "Kotlin LSP by JetBrains",
                version = "0.1" // TODO LSP-225 proper version here from the build number
            ),
        )
        LOG.info("InitializeResult:\n${LSP.json.encodeToString(InitializeResult.serializer(), result)}")
        result
    }
}

private fun LspClient.sendRunConfigurationInfoToClient() {
    val client = Client.current ?: return
    val runConfig = client.runConfig
    notify(
        LogMessageNotification,
        LogMessageParams(MessageType.Info, "Process stared with\n${runConfig}"),
    )
}

context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
private suspend fun indexFolders(
    folders: List<WorkspaceFolder>,
    params: InitializeParams,
) {
    lspClient.reportProgress(
        params,
        WorkDoneProgress.Begin(
            title = "Initializing project",
        )
    )

    if (folders.isNotEmpty()) {
        for (folder in folders) {
            initFolder(folder, params)
        }
    }
    else {
        lspClient.reportProgressMessage(params, "Using light mode")
        initLightEditMode(params, null)
    }

    lspClient.reportProgress(
        params,
        WorkDoneProgress.End(
            message = "Project imported and indexed",
        )
    )
}

context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
private suspend fun initFolder(
    folder: WorkspaceFolder,
    params: InitializeParams,
) {
    val folderPath = folder.path()
    var exceptionDuringImport = false
    try {
        when {
            JsonWorkspaceImporter.isApplicableDirectory(folderPath) -> {
                lspClient.reportProgressMessage(params, "Importing ${folderPath / "workspace.json"}")
                importProject(folderPath, JsonWorkspaceImporter, params)
                return
            }

            GradleWorkspaceImporter.isApplicableDirectory(folderPath) -> {
                lspClient.reportProgressMessage(params, "Importing Gradle project: $folderPath")
                importProject(folderPath, GradleWorkspaceImporter, params)
                return
            }

            // JpsWorkspaceImporter must be triggered after all other importers as it accepts anything containing .idea directory
            JpsWorkspaceImporter.isApplicableDirectory(folderPath) -> {
                lspClient.reportProgressMessage(params, "Importing JPS project: $folderPath")
                importProject(folderPath, JpsWorkspaceImporter, params)
                return
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        exceptionDuringImport = true
        val message = (e as? WorkspaceImportException)?.message ?: "Error importing project"
        val logMessage = (e as? WorkspaceImportException)?.logMessage ?: "Error importing project:\n${e.stackTraceToString()}"

        lspClient.notify(
            ShowMessageNotification,
            ShowMessageParams(MessageType.Error, "$message Check the log for details."),
        )

        lspClient.notify(
            LogMessageNotification,
            LogMessageParams(MessageType.Error, logMessage)
        )
        LOG.error(e)
    }

    if (exceptionDuringImport) {
        LOG.warn("Failed to import project $folderPath, switching to light edit")
    } else {
        LOG.info("No build system found for $folderPath, switching to light edit")
    }

    lspClient.reportProgressMessage(params, "No build system found for $folderPath, using light mode")
    initLightEditMode(params, folder)
}

context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
private suspend fun initLightEditMode(
    params: InitializeParams,
    folder: WorkspaceFolder?,
) {
    updateWorkspaceModel {
        registerStdlibAndJdk()
        if (folder != null) {
            addFolder(folder)
            if (folder.uri.scheme == URI.Schemas.FILE) {
                val path = folder.path()
                val librariesDir = path / "libraries"
                if (librariesDir.isDirectory()) {
                    lspClient.reportProgressMessage(params, "Found `libraries` directory: $librariesDir, adding")
                    jarLibraries(librariesDir).forEach { lib ->
                        addLibrary(lib)
                    }
                }
            }
        }
        lspClient.reportProgressMessage(params, "Indexing")
    }
    lspClient.reportProgressMessage(params, "Project is indexed")
}

private fun WorkspaceFolder.path(): Path = uri.asJavaUri().toPath()

private val LOG = Logger.getInstance("initialize")