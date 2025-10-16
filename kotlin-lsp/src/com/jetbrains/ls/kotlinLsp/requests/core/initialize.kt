// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.createSdkEntity
import com.jetbrains.ls.api.core.util.toLspUri
import com.jetbrains.ls.api.core.workspaceStructure
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
import com.jetbrains.ls.imports.light.LightWorkspaceImporter
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.ls.kotlinLsp.util.jdkRoots
import com.jetbrains.ls.kotlinLsp.util.sendSystemInfoToClient
import com.jetbrains.ls.snapshot.api.impl.core.InitializeParamsEntity
import com.jetbrains.lsp.implementation.*
import com.jetbrains.lsp.protocol.*
import fleet.kernel.change
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.io.path.toPath

context(_: LSServer, _: LSConfiguration)
internal fun LspHandlersBuilder.initializeRequest() {
    request(Initialize) { initParams ->
        Client.update { it.copy(trace = initParams.trace) }
        // TODO take into account client capabilities LSP-223

        lspClient.sendRunConfigurationInfoToClient()
        lspClient.sendSystemInfoToClient()

        LOG.info(
            "Got `initialize` request from ${initParams.clientInfo ?: "unknown"}\nparams:\n${
                LSP.json.encodeToString(
                    InitializeParams.serializer(),
                    initParams
                )
            }"
        )

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
                workspaceSymbolProvider = OrBoolean.of(
                    WorkspaceSymbolOptions.serializer(),
                    WorkspaceSymbolOptions(resolveProvider = false, workDoneProgress = true)
                ),
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
                inlayHintProvider = OrBoolean.of(
                    InlayHintRegistrationOptions.serializer(),
                    InlayHintRegistrationOptions(resolveProvider = true)
                ),
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
    workspaceStructure.updateWorkspaceModelDirectly { virtualFileUrlManager, storage ->
        if (folders.isNotEmpty()) {
            for (folder in folders) {
                initFolder(folder, params, virtualFileUrlManager, storage)
            }
        } else {
            lspClient.reportProgressMessage(params, "Using light mode")
            val imported = LightWorkspaceImporter.emptyWorkspace(virtualFileUrlManager)
            storage.applyChangesFrom(imported)
        }

        var defaultJdk = storage.entities(SdkEntity::class.java).firstOrNull { it.entitySource == DefaultJdkEntitySource }
        storage.entities<ModuleEntity>().forEach { module ->
            storage.modifyModuleEntity(module) {
                dependencies = dependencies.map {
                    when (it) {
                        is InheritedSdkDependency -> {
                            defaultJdk = defaultJdk
                                ?: createSdkEntity(
                                    name = "Java SDK",
                                    type = JavaSdk.getInstance(),
                                    roots = jdkRoots(),
                                    urlManager = virtualFileUrlManager,
                                    source = DefaultJdkEntitySource,
                                    storage
                                )
                            SdkDependency(defaultJdk.symbolicId)
                        }

                        else -> it
                    }
                }.toMutableList()
            }
        }

        lspClient.reportProgressMessage(params, "Indexing...")
    }

    lspClient.reportProgress(
        params,
        WorkDoneProgress.End(
            message = "Workspace is imported and indexed",
        )
    )
}

private val importers = listOf(JsonWorkspaceImporter, GradleWorkspaceImporter, JpsWorkspaceImporter, LightWorkspaceImporter)

context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
private suspend fun initFolder(
    folder: WorkspaceFolder,
    params: InitializeParams,
    virtualFileUrlManager: VirtualFileUrlManager,
    storage: MutableEntityStorage,
) {
    lspClient.reportProgressMessage(params, "Importing folder ${folder.uri.asJavaUri().path}")
    val folderPath = folder.path()
    for (importer in importers) {
        val unresolved = mutableSetOf<String>()
        lspClient.reportProgressMessage(params, "Trying to import using ${importer.javaClass.simpleName}")
        val imported = try {
            withContext(Dispatchers.IO) {
                importer.importWorkspace(folderPath, virtualFileUrlManager, unresolved::add)
            } ?: continue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = (e as? WorkspaceImportException)?.message ?: "Error importing folder"
            val logMessage = (e as? WorkspaceImportException)?.logMessage ?: "Error importing folder:\n${e.stackTraceToString()}"

            lspClient.notify(
                ShowMessageNotification,
                ShowMessageParams(MessageType.Error, "$message Check the log for details."),
            )

            lspClient.notify(
                LogMessageNotification,
                LogMessageParams(MessageType.Error, logMessage)
            )
            LOG.error(e)
            continue
        }
        if (unresolved.isNotEmpty()) {
            lspClient.notify(
                ShowMessageNotification,
                ShowMessageParams(MessageType.Warning, unresolved.joinToString(", ", "Couldn't resolve some dependencies: ")),
            )
        }
        storage.applyChangesFrom(imported)

        break
    }
}

private object DefaultJdkEntitySource : EntitySource

private fun WorkspaceFolder.path(): Path = uri.asJavaUri().toPath()

private val LOG = Logger.getInstance("initialize")