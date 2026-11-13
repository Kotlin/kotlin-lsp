// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.intellij.BundleBase
import com.intellij.codeInspection.options.LocMessage
import com.intellij.codeInspection.options.OptCheckbox
import com.intellij.codeInspection.options.OptCheckboxPanel
import com.intellij.codeInspection.options.OptControl
import com.intellij.codeInspection.options.OptCustom
import com.intellij.codeInspection.options.OptDropdown
import com.intellij.codeInspection.options.OptExpandableString
import com.intellij.codeInspection.options.OptGroup
import com.intellij.codeInspection.options.OptHorizontalStack
import com.intellij.codeInspection.options.OptMultiSelector
import com.intellij.codeInspection.options.OptNumber
import com.intellij.codeInspection.options.OptPane
import com.intellij.codeInspection.options.OptRegularComponent
import com.intellij.codeInspection.options.OptSeparator
import com.intellij.codeInspection.options.OptSettingLink
import com.intellij.codeInspection.options.OptString
import com.intellij.codeInspection.options.OptStringList
import com.intellij.codeInspection.options.OptTabSet
import com.intellij.codeInspection.options.OptTable
import com.intellij.codeInspection.options.OptionContainer
import com.intellij.codeInspection.options.OptionController
import com.intellij.codeInspection.options.StringValidator
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModEditOptions
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.findDocument
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.refuseStaleDocument
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.executeCommand
import com.jetbrains.ls.snapshot.api.impl.core.LazyActionId
import com.jetbrains.ls.snapshot.api.impl.core.LazyActionSessionComponent
import com.jetbrains.ls.snapshot.api.impl.core.LazyActionSessionInfo
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.RequestType
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

private val LOG = logger<EditOptionsSession>()

/**
 * One component of an [OptPane], in the form that the client renders.
 *
 * Each type mirrors an [OptRegularComponent] of the same name. A label that holds a control in its middle, such as
 * "At least | lines", travels as a [prefix][StringField.prefix] and a [suffix][StringField.suffix]. A description is
 * simple HTML. The client renders it as it is, so it must come from the server bundles only.
 *
 * A control carries no value. [EditOptionsParams.values] holds the values by `bindId`.
 */
@Serializable
sealed interface OptionComponentData {
    /** An [OptCheckbox]. The client disables the [children] while the checkbox is clear. */
    @Serializable
    @SerialName("checkbox")
    data class Checkbox(
        val bindId: String,
        val label: String,
        val children: List<OptionComponentData> = emptyList(),
        val description: String? = null,
    ) : OptionComponentData

    /** An [OptString]. [width] is the approximate number of characters, or `-1` for the default width. */
    @Serializable
    @SerialName("string")
    data class StringField(
        val bindId: String,
        val prefix: String,
        val suffix: String,
        val width: Int = -1,
        val description: String? = null,
    ) : OptionComponentData

    /** An [OptExpandableString]. The client can show the value as lines, which [separator] joins. */
    @Serializable
    @SerialName("expandableString")
    data class ExpandableStringField(
        val bindId: String,
        val label: String,
        val separator: String,
        val description: String? = null,
    ) : OptionComponentData

    /** An [OptNumber]. [integer] is `false` for a `double` option. */
    @Serializable
    @SerialName("number")
    data class NumberField(
        val bindId: String,
        val prefix: String,
        val suffix: String,
        val min: Int,
        val max: Int,
        val integer: Boolean = true,
        val description: String? = null,
    ) : OptionComponentData

    /** An [OptDropdown]. The value is the [DropdownOption.key] of the selected option. */
    @Serializable
    @SerialName("dropdown")
    data class Dropdown(
        val bindId: String,
        val prefix: String,
        val suffix: String,
        val options: List<DropdownOption>,
    ) : OptionComponentData

    @Serializable
    data class DropdownOption(val key: String, val label: String)

    /** An [OptStringList]. The value is a list of strings. */
    @Serializable
    @SerialName("stringList")
    data class StringList(
        val bindId: String,
        val label: String,
        val description: String? = null,
    ) : OptionComponentData

    /** An [OptTable]. Each column has its own `bindId`, and its value is the list of the cells of the column. */
    @Serializable
    @SerialName("table")
    data class Table(
        val label: String,
        val columns: List<TableColumn>,
        val description: String? = null,
    ) : OptionComponentData

    @Serializable
    data class TableColumn(val bindId: String, val name: String)

    /**
     * An [OptMultiSelector]. The value is the list of the indexes of the selected [elements].
     * [multiple] allows more than one selected element, and [allowsEmpty] allows no selected element.
     */
    @Serializable
    @SerialName("multiSelector")
    data class MultiSelector(
        val bindId: String,
        val elements: List<SelectorElement>,
        val multiple: Boolean,
        val allowsEmpty: Boolean,
    ) : OptionComponentData

    @Serializable
    data class SelectorElement(val text: String, val secondaryText: String? = null)

    /** An [OptGroup]. */
    @Serializable
    @SerialName("group")
    data class Group(val label: String, val children: List<OptionComponentData>) : OptionComponentData

    /** An [OptHorizontalStack]. The client puts the [children] on one line. */
    @Serializable
    @SerialName("horizontalStack")
    data class HorizontalStack(val children: List<OptionComponentData>) : OptionComponentData

    /** An [OptSeparator]. */
    @Serializable
    @SerialName("separator")
    data class Separator(val label: String? = null) : OptionComponentData

    /** An [OptSettingLink]. The client cannot open the IDE settings, so it shows the [label] as text. */
    @Serializable
    @SerialName("settingLink")
    data class SettingLink(val label: String) : OptionComponentData

    /** An [OptTabSet]. */
    @Serializable
    @SerialName("tabSet")
    data class TabSet(val tabs: List<Tab>) : OptionComponentData

    @Serializable
    data class Tab(val label: String, val children: List<OptionComponentData>)

    /** An [OptCheckboxPanel]. The client can show it as a list of [Checkbox] components. */
    @Serializable
    @SerialName("checkboxPanel")
    data class CheckboxPanel(val checkboxes: List<Checkbox>) : OptionComponentData
}

/**
 * The form to show, and the text to show it with. The server sends every user-visible string, because a localized
 * string of this server belongs in its `LspServerBundle`, not in the client.
 *
 * [errors] holds a message by `bindId`. The server sends it again with the [values] of the user when a value is not
 * valid, so the user can correct it.
 */
@Serializable
data class EditOptionsParams(
    val title: String,
    val components: List<OptionComponentData>,
    val values: Map<String, JsonElement>,
    val errors: Map<String, String> = emptyMap(),
    val okLabel: String,
    val cancelLabel: String,
    val addLabel: String,
    val removeLabel: String,
)

/** The answer of the user to an [EditOptionsRequest]. [values] is `null` when the user cancelled the form. */
@Serializable
data class EditOptionsResult(val values: Map<String, JsonElement>? = null)

/**
 * A custom server -> client request (used by the ModCommand [ModEditOptions]) which asks the user to edit the options
 * of a form. The client shows [EditOptionsParams.components] with [EditOptionsParams.values], and answers the values
 * the user entered.
 *
 * This is a request, not a notification, because the server computes the next command from the answer.
 * [ModCommandData.from] converts a [ModEditOptions] only for a client which declares `editOptions`.
 */
val EditOptionsRequest: RequestType<EditOptionsParams, EditOptionsResult, Unit> =
    RequestType(
        "intellij/editOptions",
        EditOptionsParams.serializer(),
        EditOptionsResult.serializer(),
        Unit.serializer(),
    )

/**
 * The [ModEditOptions] behind a [ModCommandData.EditOptions], kept in [LazyActionSessionComponent].
 *
 * The next command of a [ModEditOptions] is a function, so it cannot travel to the client. The session keeps it on
 * the server until the user answers. [LazyActionSessionComponent] drops the session when the file of [context]
 * changes, so a session found after the answer still matches the text that the command reads.
 */
class EditOptionsSession(
    override val fileUrl: String,
    override val documentStamp: Long,
    private val command: ModEditOptions<OptionContainer>,
    private val context: ActionContext,
) : LazyActionSessionInfo {
    override val actionCount: Int get() = 1

    /**
     * Applies [values] to a new container and computes the next command. Must run in a read action.
     *
     * The validators of a pane can read the other options of their container, so they run on the pane of the
     * container that already holds [values].
     *
     * The command can run in another analysis context than the one that converted it. The file of [context] is then
     * resolved again. The PSI that the next command holds is not, so such a command can fail and answer
     * [EditOptionsOutcome.NotAvailable].
     */
    fun apply(values: Map<String, JsonElement>, server: LSServer): EditOptionsOutcome {
        val currentContext = context.inCurrentAnalysisContext() ?: return EditOptionsOutcome.NotAvailable
        val container = command.containerSupplier.get()
        val controller = container.optionController
        val errors = LinkedHashMap<String, String>()
        OptionValueWriter(controller, values, errors).write(container.optionsPane.components)
        if (errors.isEmpty()) {
            OptionValidator(controller, currentContext, errors).validate(container.optionsPane.components, enabled = true)
        }
        if (errors.isNotEmpty()) return EditOptionsOutcome.Invalid(errors)
        val next = runCatching {
            command.nextCommand.apply(container)
        }.getOrHandleException {
            LOG.warn("Failed to compute the next command of $command", it)
        } ?: return EditOptionsOutcome.NotAvailable
        val data = ModCommandData.from(next, currentContext, server) ?: return EditOptionsOutcome.NotSupported
        return EditOptionsOutcome.Ready(data)
    }
}

/** What [EditOptionsSession.apply] found. */
sealed interface EditOptionsOutcome {
    /** The next command, converted for the client. */
    data class Ready(val data: ModCommandData) : EditOptionsOutcome

    /** Some values are not valid. [errors] holds a message by `bindId`. */
    data class Invalid(val errors: Map<String, String>) : EditOptionsOutcome

    /** The command does not apply anymore. */
    data object NotAvailable : EditOptionsOutcome

    /** The next command has no LSP representation. */
    data object NotSupported : EditOptionsOutcome
}

/**
 * The [ModCommandData.EditOptions] which asks the user to edit the options of [this] command, or `null` when the
 * client cannot show its pane. Must run in a read action.
 *
 * A pane which holds an [OptCustom] has no form, because the renderer of a custom component is IDE code.
 */
@Suppress("UNCHECKED_CAST")
fun ModEditOptions<*>.toEditOptionsData(context: ActionContext, server: LSServer): ModCommandData.EditOptions? {
    val virtualFile = context.file.virtualFile ?: return null
    val container = containerSupplier.get()
    val values = LinkedHashMap<String, JsonElement>()
    val components = OptionPaneReader(container.optionController, values).read(container.optionsPane.components) ?: return null
    val session = EditOptionsSession(
        fileUrl = virtualFile.url,
        // A file without a document has no text to hash; its modification stamp still changes with the file.
        documentStamp = virtualFile.findDocument()?.let(DocumentStamp::of)?.value ?: virtualFile.modificationStamp,
        command = this as ModEditOptions<OptionContainer>,
        context = context,
    )
    val id = server[LazyActionSessionComponent].register(session)
    return ModCommandData.EditOptions(title, components, values, id.id)
}

/**
 * Whether the client can show the options pane of [this] container as a form, as [toEditOptionsData] does.
 * A pane which holds an [OptCustom] has no form. Must run in a read action.
 */
internal fun OptionContainer.hasEditOptionsForm(): Boolean =
    OptionPaneReader(optionController, LinkedHashMap()).read(optionsPane.components) != null

/**
 * Asks the user to edit the options of [command], and executes the command that the answer produces. Returns `false`
 * when the user cancelled the form, like [executeCommand] does for a stopped command.
 *
 * A value which is not valid shows the form again, with the values of the user and the messages of the validators.
 * The analysis context stays open while the form is open. A change of the file of the command drops its session,
 * so the command stops with a message instead of an edit at a wrong offset.
 */
context(server: LSServer, _: LSAnalysisContext)
internal suspend fun executeEditOptions(
    command: ModCommandData.EditOptions,
    client: LspClient,
    changedFiles: MutableMap<String, String>,
    sentVersions: MutableMap<String, Int>,
): Boolean {
    if (findSession(command.sessionId) == null) {
        client.showError(LspServerBundle.message("error.action.not.available"))
        return false
    }
    var values = command.values
    var errors = emptyMap<String, String>()
    while (true) {
        val result = client.request(
            requestType = EditOptionsRequest,
            params = EditOptionsParams(
                title = command.title,
                components = command.components,
                values = values,
                errors = errors,
                okLabel = LspServerBundle.message("edit.options.action.ok"),
                cancelLabel = LspServerBundle.message("edit.options.action.cancel"),
                addLabel = LspServerBundle.message("edit.options.action.add"),
                removeLabel = LspServerBundle.message("edit.options.action.remove"),
            ),
        )
        val answered = result.values ?: return false
        val session = findSession(command.sessionId) ?: client.refuseStaleDocument()
        when (val outcome = readAction { session.apply(answered, server) }) {
            is EditOptionsOutcome.Ready -> return executeCommand(outcome.data, client, changedFiles, sentVersions)
            is EditOptionsOutcome.Invalid -> {
                values = values + answered
                errors = outcome.errors
            }
            EditOptionsOutcome.NotAvailable -> {
                client.showError(LspServerBundle.message("error.action.not.available"))
                return false
            }
            EditOptionsOutcome.NotSupported -> {
                client.showError(LspServerBundle.message("error.fix.not.supported"))
                return false
            }
        }
    }
}

context(server: LSServer)
private fun findSession(sessionId: Long): EditOptionsSession? =
    server[LazyActionSessionComponent].get(LazyActionId.of(sessionId)) as? EditOptionsSession

private suspend fun LspClient.showError(message: String) {
    notify(ShowMessageNotificationType, ShowMessageParams(MessageType.Error, message))
}

/** The label without the mnemonic marker of the bundles. The client shows no mnemonics. */
private fun LocMessage.text(): String = label().withoutMnemonic()
private val LocMessage.prefix: String get() = splitLabel().prefix.withoutMnemonic()
private val LocMessage.suffix: String get() = splitLabel().suffix.withoutMnemonic()
private fun String.withoutMnemonic(): String = replace(BundleBase.MNEMONIC_STRING, "")
private fun HtmlChunk?.toHtml(): String? = this?.toString()?.takeIf { it.isNotEmpty() }

/**
 * Converts the components of a pane, and puts the value of each control into [values].
 * [read] returns `null` when a component or a value has no form on the client.
 */
private class OptionPaneReader(private val controller: OptionController, private val values: MutableMap<String, JsonElement>) {
    fun read(components: List<OptRegularComponent>): List<OptionComponentData>? =
        components.map { read(it) ?: return null }

    private fun read(component: OptRegularComponent): OptionComponentData? = when (component) {
        is OptCheckbox -> readCheckbox(component)
        is OptString -> {
            values[component.bindId] = JsonPrimitive(controller.getOption(component.bindId) as? String ?: "")
            OptionComponentData.StringField(
                component.bindId, component.splitLabel.prefix, component.splitLabel.suffix, component.width, component.description.toHtml(),
            )
        }
        is OptExpandableString -> {
            values[component.bindId] = JsonPrimitive(controller.getOption(component.bindId) as? String ?: "")
            OptionComponentData.ExpandableStringField(
                component.bindId, component.label.text(), component.separator, component.description.toHtml(),
            )
        }
        is OptNumber -> when (val value = controller.getOption(component.bindId)) {
            is Double -> {
                values[component.bindId] = JsonPrimitive(value)
                component.toData(integer = false)
            }
            is Int -> {
                values[component.bindId] = JsonPrimitive(value)
                component.toData(integer = true)
            }
            else -> null
        }
        is OptDropdown -> when (val value = controller.getOption(component.bindId)) {
            // The IDE cannot render a dropdown without a value either: the type of the value decides how a key converts back.
            null -> null
            else -> {
                values[component.bindId] = JsonPrimitive(if (value is Enum<*>) value.name else value.toString())
                OptionComponentData.Dropdown(
                    component.bindId, component.splitLabel.prefix, component.splitLabel.suffix,
                    component.options.map { OptionComponentData.DropdownOption(it.key, it.label.text()) },
                )
            }
        }
        is OptStringList -> {
            values[component.bindId] = readStringList(component.bindId) ?: return null
            OptionComponentData.StringList(component.bindId, component.label.text(), component.description.toHtml())
        }
        is OptTable -> {
            for (column in component.children) {
                values[column.bindId] = readStringList(column.bindId) ?: return null
            }
            OptionComponentData.Table(
                component.label.text(),
                component.children.map { OptionComponentData.TableColumn(it.bindId, it.name.text()) },
                component.description.toHtml(),
            )
        }
        is OptMultiSelector -> {
            val selected = controller.getOption(component.bindId) as? List<*> ?: emptyList<Any>()
            values[component.bindId] = JsonArray(selected.map { component.elements.indexOf(it) }.filter { it >= 0 }.map(::JsonPrimitive))
            OptionComponentData.MultiSelector(
                component.bindId,
                component.elements.map { OptionComponentData.SelectorElement(it.text, it.secondaryText) },
                multiple = when (component.mode) {
                    OptMultiSelector.SelectionMode.SINGLE, OptMultiSelector.SelectionMode.SINGLE_OR_EMPTY -> false
                    OptMultiSelector.SelectionMode.MULTIPLE, OptMultiSelector.SelectionMode.MULTIPLE_OR_EMPTY -> true
                },
                allowsEmpty = component.mode.allowsEmpty,
            )
        }
        is OptGroup -> OptionComponentData.Group(component.label.text(), read(component.children) ?: return null)
        is OptHorizontalStack -> OptionComponentData.HorizontalStack(read(component.children) ?: return null)
        is OptSeparator -> OptionComponentData.Separator(component.label?.text()?.takeIf { it.isNotEmpty() })
        is OptSettingLink -> OptionComponentData.SettingLink(component.displayName.text())
        is OptTabSet -> OptionComponentData.TabSet(
            component.children.map { tab -> OptionComponentData.Tab(tab.label.text(), read(tab.children) ?: return null) },
        )
        is OptCheckboxPanel -> OptionComponentData.CheckboxPanel(component.children.map { readCheckbox(it) ?: return null })
        is OptCustom -> {
            LOG.debug("The custom component ${component.componentId} has no form on the client")
            null
        }
    }

    private fun readCheckbox(component: OptCheckbox): OptionComponentData.Checkbox? {
        values[component.bindId] = JsonPrimitive(controller.getOption(component.bindId) as? Boolean ?: false)
        return OptionComponentData.Checkbox(
            component.bindId, component.label.text(), read(component.children) ?: return null, component.description.toHtml(),
        )
    }

    private fun readStringList(bindId: String): JsonArray? {
        val list = controller.getOption(bindId) as? List<*> ?: return null
        return JsonArray(list.map { JsonPrimitive(it?.toString() ?: "") })
    }

    private fun OptNumber.toData(integer: Boolean) = OptionComponentData.NumberField(
        bindId, splitLabel.prefix, splitLabel.suffix, minValue, maxValue, integer, description.toHtml(),
    )
}

private val OptMultiSelector.SelectionMode.allowsEmpty: Boolean
    get() = when (this) {
        OptMultiSelector.SelectionMode.SINGLE, OptMultiSelector.SelectionMode.MULTIPLE -> false
        OptMultiSelector.SelectionMode.SINGLE_OR_EMPTY, OptMultiSelector.SelectionMode.MULTIPLE_OR_EMPTY -> true
    }

/**
 * Writes the [values] of the user into the options of [controller]. A control without a value keeps its option.
 * A value of a wrong type puts a message into [errors].
 */
private class OptionValueWriter(
    private val controller: OptionController,
    private val values: Map<String, JsonElement>,
    private val errors: MutableMap<String, String>,
) {
    fun write(components: List<OptRegularComponent>) {
        for (component in components) write(component)
    }

    private fun write(component: OptRegularComponent) {
        when (component) {
            is OptCheckbox -> {
                writePrimitive(component) { it.booleanOrNull }
                write(component.children)
            }
            is OptString -> writePrimitive(component) { it.stringOrNull }
            is OptExpandableString -> writePrimitive(component) { it.stringOrNull }
            is OptNumber -> writeNumber(component)
            is OptDropdown -> writeDropdown(component)
            is OptStringList -> writeStringList(component.bindId)
            is OptTable -> component.children.forEach { writeStringList(it.bindId) }
            is OptMultiSelector -> writeSelection(component)
            is OptGroup -> write(component.children)
            is OptHorizontalStack -> write(component.children)
            is OptTabSet -> component.children.forEach { write(it.children) }
            is OptCheckboxPanel -> component.children.forEach { write(it) }
            is OptSeparator, is OptSettingLink, is OptCustom -> {}
        }
    }

    private fun writePrimitive(control: OptControl, convert: (JsonPrimitive) -> Any?) {
        val bindId = control.bindId()
        val value = values[bindId] ?: return
        val converted = (value as? JsonPrimitive)?.let(convert)
        if (converted == null) invalid(bindId) else controller.setOption(bindId, converted)
    }

    private fun writeNumber(component: OptNumber) {
        val value = values[component.bindId] as? JsonPrimitive ?: return
        val number: Number? = when (controller.getOption(component.bindId)) {
            is Double -> value.doubleOrNull?.takeIf { it in component.minValue.toDouble()..component.maxValue.toDouble() }
            else -> value.intOrNull?.takeIf { it in component.minValue..component.maxValue }
        }
        if (number == null) {
            errors[component.bindId] = LspServerBundle.message("edit.options.error.number.range", component.minValue, component.maxValue)
        }
        else {
            controller.setOption(component.bindId, number)
        }
    }

    private fun writeDropdown(component: OptDropdown) {
        val key = (values[component.bindId] as? JsonPrimitive)?.stringOrNull ?: return
        if (component.options.none { it.key == key }) return invalid(component.bindId)
        val type = controller.getOption(component.bindId)?.javaClass ?: return invalid(component.bindId)
        controller.setOption(component.bindId, convertKey(key, type))
    }

    private fun writeStringList(bindId: String) {
        val value = values[bindId] as? JsonArray ?: return
        val strings = value.map { (it as? JsonPrimitive)?.stringOrNull ?: return invalid(bindId) }
        // The IDE edits the list of the option in place, so a getter-only option accepts the same list back.
        @Suppress("UNCHECKED_CAST")
        val list = controller.getOption(bindId) as? MutableList<String> ?: return invalid(bindId)
        list.clear()
        list.addAll(strings)
        controller.setOption(bindId, list)
    }

    private fun writeSelection(component: OptMultiSelector) {
        val value = values[component.bindId] as? JsonArray ?: return
        val selected = value.map { index ->
            (index as? JsonPrimitive)?.intOrNull?.let(component.elements::getOrNull) ?: return invalid(component.bindId)
        }
        val error = when {
            selected.isEmpty() && !component.mode.allowsEmpty -> LspServerBundle.message("edit.options.error.selection.empty")
            selected.size > 1 && component.mode in SINGLE_MODES -> LspServerBundle.message("edit.options.error.selection.single")
            else -> null
        }
        if (error != null) errors[component.bindId] = error else controller.setOption(component.bindId, selected.toMutableList())
    }

    private fun invalid(bindId: String) {
        errors[bindId] = LspServerBundle.message("edit.options.error.invalid.value")
    }

    private val JsonPrimitive.stringOrNull: String? get() = if (isString) content else null

    /** Converts a dropdown [key] to the [type] of the option, as `UiDslOptPaneRenderer` does. */
    @Suppress("UNCHECKED_CAST")
    private fun convertKey(key: String, type: Class<*>): Any = when {
        type == Boolean::class.javaObjectType -> key.toBoolean()
        type == Int::class.javaObjectType -> key.toInt()
        type.isEnum -> java.lang.Enum.valueOf(type as Class<out Enum<*>>, key)
        type.superclass?.isEnum == true -> java.lang.Enum.valueOf(type.superclass as Class<out Enum<*>>, key)
        else -> key
    }

    private companion object {
        val SINGLE_MODES = setOf(OptMultiSelector.SelectionMode.SINGLE, OptMultiSelector.SelectionMode.SINGLE_OR_EMPTY)
    }
}

/**
 * Runs the [StringValidator] of each string control, and puts the first message of each control into [errors].
 * A control which a clear checkbox disables is not validated, because the user cannot edit it.
 */
private class OptionValidator(
    private val controller: OptionController,
    private val context: ActionContext,
    private val errors: MutableMap<String, String>,
) {
    fun validate(components: List<OptRegularComponent>, enabled: Boolean) {
        for (component in components) validate(component, enabled)
    }

    private fun validate(component: OptRegularComponent, enabled: Boolean) {
        when (component) {
            is OptCheckbox -> validate(component.children, enabled && controller.getOption(component.bindId) == true)
            is OptString -> if (enabled) check(component.bindId, component.validator, listOf(controller.getOption(component.bindId)))
            is OptStringList -> if (enabled) check(component.bindId, component.validator, controller.getOption(component.bindId) as? List<*>)
            is OptTable -> if (enabled) {
                component.children.forEach { check(it.bindId, it.validator, controller.getOption(it.bindId) as? List<*>) }
            }
            is OptGroup -> validate(component.children, enabled)
            is OptHorizontalStack -> validate(component.children, enabled)
            is OptTabSet -> component.children.forEach { validate(it.children, enabled) }
            is OptCheckboxPanel -> component.children.forEach { validate(it, enabled) }
            is OptExpandableString, is OptNumber, is OptDropdown, is OptMultiSelector, is OptSeparator, is OptSettingLink, is OptCustom -> {}
        }
    }

    private fun check(bindId: String, validator: StringValidator?, strings: List<*>?) {
        if (validator == null || strings == null) return
        val message = strings.firstNotNullOfOrNull { string ->
            runCatching {
                validator.getErrorMessage(context.project, string?.toString() ?: "")
            }.getOrHandleException {
                LOG.warn("The validator ${validator.validatorId()} failed", it)
            }
        }
        if (message != null) errors[bindId] = message
    }
}
