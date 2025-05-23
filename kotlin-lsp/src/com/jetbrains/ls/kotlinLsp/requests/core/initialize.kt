package com.jetbrains.ls.kotlinLsp.requests.core

import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.jarLibraries
import com.jetbrains.ls.api.core.util.toLspUri
import com.jetbrains.ls.api.core.util.updateWorkspaceModel
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokens
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.ls.kotlinLsp.util.importProject
import com.jetbrains.ls.kotlinLsp.util.registerStdlibAndJdk
import com.jetbrains.ls.kotlinLsp.util.sendSystemInfoToClient
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.implementation.reportProgress
import com.jetbrains.lsp.implementation.reportProgressMessage
import com.jetbrains.lsp.protocol.*
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.*

context(LSServer, LSConfiguration)
internal fun LspHandlersBuilder.initializeRequest() {
    request(Initialize) { initParams ->
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

        Client.update { it.copy(trace = initParams.trace) }
        indexFolders(folders, initParams)

        val result = InitializeResult(
            capabilities = ServerCapabilities(
                textDocumentSync = TextDocumentSync(TextDocumentSyncKind.Incremental),
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
                    range = false,
                    full = true,
                ),
                completionProvider = CompletionRegistrationOptionsImpl(
                    triggerCharacters = listOf("."), // todo should be customized?
                    allCommitCharacters = null,
                    resolveProvider = entries<LSCompletionProvider>().any { it.supportsResolveRequest },
                    completionItem = null,
                ),
                codeActionProvider = OrBoolean.of(
                    CodeActionOptions(
                        codeActionKinds = CodeActionKind.entries,
                        resolveProvider = false,
                        workDoneProgress = false,
                    )
                ),
                executeCommandProvider = ExecuteCommandOptions(
                    commands = allCommandDescriptors.map { it.name }
                ),
                referencesProvider = OrBoolean(true),
                hoverProvider = OrBoolean(true),
                workspaceSymbolProvider = OrBoolean.of(WorkspaceSymbolOptions(resolveProvider = false, workDoneProgress = true)),
                workspace = ServerWorkspaceCapabilities(
                    workspaceFolders = WorkspaceFoldersServerCapabilities(
                        supported = true,
                        changeNotifications = JsonPrimitive(true),
                    )
                )
            ),
            serverInfo = InitializeResult.ServerInfo(
                name = "Kotlin LSP by JetBrains",
                version = "0.1" // todo proper version here from the build number
            ),
        )
        LOG.info("InitializeResult:\n${LSP.json.encodeToString(InitializeResult.serializer(), result)}")
        result
    }
}

context(LSServer, LSConfiguration, LspHandlerContext)
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

    for (folder in folders) {
        initFolder(folder, params)
    }

    lspClient.reportProgress(
        params,
        WorkDoneProgress.End(
            message = "Project imported and indexed",
        )
    )
}

context(LSServer, LSConfiguration, LspHandlerContext)
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
        }
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
    }

    if (exceptionDuringImport) {
        LOG.warn("Failed to import project $folderPath, switching to light edit")
    } else {
        LOG.info("No build system found for $folderPath, switching to light edit")
    }
    initFolderForLightEdit(folder, params)
}


context(LSServer, LSConfiguration, LspHandlerContext)
private suspend fun initFolderForLightEdit(
    folder: WorkspaceFolder,
    params: InitializeParams,
) {
    val folderPath = folder.path()

    lspClient.reportProgressMessage(params, "No build system found for $folderPath, using light mode")
    updateWorkspaceModel {
        registerStdlibAndJdk()
        addFolder(folder)
        if (folder.uri.scheme == URI.Schemas.FILE) {
            val path = folderPath
            val librariesDir = path / "libraries"
            if (librariesDir.isDirectory()) {
                lspClient.reportProgressMessage(params, "Found `libraries` directory: $librariesDir, adding")
                jarLibraries(librariesDir).forEach { lib ->
                    addLibrary(lib)
                }
            }
        }
        lspClient.reportProgressMessage(params, "Indexing")
    }
    lspClient.reportProgressMessage(params, "Project is indexed")
}

private fun WorkspaceFolder.path(): Path = uri.asJavaUri().toPath()

private val LOG = Logger.getInstance("initialize")