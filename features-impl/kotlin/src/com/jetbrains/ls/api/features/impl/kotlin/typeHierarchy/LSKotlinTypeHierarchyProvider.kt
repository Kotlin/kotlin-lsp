// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.typeHierarchy

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.core.JavaPsiBundle
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.LambdaUtil
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFunctionalExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.presentation.java.ClassPresentationUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.FunctionalExpressionSearch
import com.intellij.psi.util.PsiUtilCore
import com.intellij.psi.util.parentOfType
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.utils.findElementUnderCaret
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.kotlin.symbols.getKind
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.resolve.ResolveDataWithConfigurationEntryId
import com.jetbrains.ls.api.features.typeHierarchy.LSTypeHierarchyProvider
import com.jetbrains.ls.api.features.utils.PsiSerializablePointer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.SymbolKind
import com.jetbrains.lsp.protocol.SymbolTag
import com.jetbrains.lsp.protocol.TypeHierarchyItem
import com.jetbrains.lsp.protocol.TypeHierarchyPrepareParams
import com.jetbrains.lsp.protocol.TypeHierarchySubtypesParams
import com.jetbrains.lsp.protocol.TypeHierarchySupertypesParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.symbols.classSymbol
import org.jetbrains.kotlin.analysis.api.symbols.containingSymbol
import org.jetbrains.kotlin.analysis.api.types.builtinTypes
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.base.projectStructure.scope.KotlinSourceFilterScope
import org.jetbrains.kotlin.idea.base.util.excludeKotlinSources
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.searching.inheritors.DirectKotlinClassInheritorsSearch
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportAlias
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedElementSelector
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Type hierarchy for a Kotlin file.
 *
 * The root and the tree nodes can be a [KtClassOrObject], a [PsiClass] or a [PsiFunctionalExpression], because
 * a Kotlin class can extend a Java class and a Java class can extend a Kotlin class. An item carries a
 * [PsiSerializablePointer], so a local or an anonymous class is a valid node too.
 */
internal object LSKotlinTypeHierarchyProvider : LSTypeHierarchyProvider, LSUniqueConfigurationEntry {
    override val uniqueId = LSUniqueConfigurationEntry.UniqueId("kotlin.typeHierarchy")

    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): List<TypeHierarchyItem>? {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) as? KtFile ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val offset = document.offsetByPosition(params.position)
                val adjustedOffset = TargetElementUtil.adjustOffset(psiFile, document, offset)
                val target = findClassAt(psiFile, adjustedOffset) ?: return@readAction null
                val item = target.toTypeHierarchyItem() ?: return@readAction null
                listOf(item)
            }
        }
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun supertypes(params: TypeHierarchySupertypesParams): List<TypeHierarchyItem>? {
        val itemData = TypeHierarchyItemData.fromJson(params.item.data) ?: return null
        return server.withAnalysisContext {
            readAction {
                val element = itemData.restore(project) ?: return@readAction null
                val supertypes = when (element) {
                    is KtClassOrObject -> element.supertypeElements()
                    is PsiClass -> element.supertypeElements()
                    else -> emptyList()
                }
                supertypes.mapNotNull { it.toTypeHierarchyItem() }
            }
        }
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun subtypes(params: TypeHierarchySubtypesParams): List<TypeHierarchyItem>? {
        val itemData = TypeHierarchyItemData.fromJson(params.item.data) ?: return null
        return server.withAnalysisContext {
            readAction {
                val element = itemData.restore(project) ?: return@readAction null
                val subtypes = when (element) {
                    is KtClassOrObject -> element.subtypeElements()
                    is PsiClass -> element.subtypeElements()
                    else -> emptyList()
                }
                subtypes.mapNotNull { it.toTypeHierarchyItem() }
            }
        }
    }

    private fun findClassAt(psiFile: KtFile, offset: Int): PsiElement? {
        val editor = ImaginaryEditor(psiFile.project, psiFile.fileDocument)
        val resolved = findElementUnderCaret(editor, offset)?.asClass()
        if (resolved != null) return resolved
        val leaf = PsiUtilCore.getElementAtOffset(psiFile, offset)
        return generateSequence(leaf.parentOfType<KtClassOrObject>(withSelf = true)) { it.parentOfType<KtClassOrObject>() }
            .firstOrNull { it.name != null }
    }

    /** The class a target stands for. An import alias stands for the class it imports. */
    private fun PsiElement.asClass(): PsiElement? = when (val element = unwrapped ?: this) {
        is KtClassOrObject -> element
        is KtConstructor<*> -> element.getContainingClassOrObject()
        is KtImportAlias -> element.importDirective?.importedReference?.getQualifiedElementSelector()?.mainReference?.resolve()?.asClass()
        is PsiClass -> element
        is PsiMethod -> element.takeIf { it.isConstructor }?.containingClass
        else -> null
    }

    private fun KtClassOrObject.supertypeElements(): List<PsiElement> {
        if (this is KtEnumEntry) {
            // An enum entry has no class symbol. Its supertype is the enum class.
            return listOfNotNull(containingClassOrObject)
        }
        return analyzeSupertypeElements()
    }

    private fun KtClassOrObject.analyzeSupertypeElements(): List<PsiElement> = analyze(this) {
        val classSymbol = classSymbol ?: return@analyze emptyList()
        val isInterface = this@analyzeSupertypeElements is KtClass && isInterface()
        if (isInterface && superTypeListEntries.isEmpty()) {
            // An interface has no `Any` in its super list.
            return@analyze emptyList()
        }
        if (this@analyzeSupertypeElements is KtClass && isAnnotation()) {
            return@analyze classSymbol.annotations.mapNotNull { it.constructorSymbol?.containingSymbol?.psi }
        }
        val elements = classSymbol.superTypes.mapNotNull { it.symbol?.psi }
        if (elements.isNotEmpty() && !isInterface && elements.all { it.isInterfaceElement() }) {
            listOfNotNull(builtinTypes.any.symbol?.psi) + elements
        }
        else {
            elements
        }
    }

    private fun PsiClass.supertypeElements(): List<PsiElement> {
        if (isAnnotationType) {
            // An annotation class extends its meta-annotations, like the Kotlin branch above.
            return modifierList?.annotations.orEmpty().mapNotNull { it.resolveAnnotationType()?.unwrapped }
        }
        val supers = if (isInterface) supers.filter { it.qualifiedName != CommonClassNames.JAVA_LANG_OBJECT } else supers.toList()
        return supers.mapNotNull { it.unwrapped }
    }

    private fun KtClassOrObject.subtypeElements(): List<PsiElement> {
        if (fqName == StandardClassIds.Any.asSingleFqName()) {
            // Every class is a subclass of `Any`.
            return emptyList()
        }
        // An object and an enum entry have no inheritors.
        val klass = this as? KtClass ?: return emptyList()
        if (klass is KtEnumEntry || klass.name == null) return emptyList()
        val scope = klass.useScope
        if (klass.isAnnotation()) return klass.annotatedAnnotationClasses(scope)

        val inheritors = DirectKotlinClassInheritorsSearch.search(klass, scope).findAll().mapNotNull { it.unwrapped }
        val lightClass = klass.toLightClass()
        if (lightClass == null || !LambdaUtil.isFunctionalClass(lightClass)) return inheritors
        return inheritors + FunctionalExpressionSearch.search(lightClass, scope).findAll()
    }

    private fun PsiClass.subtypeElements(): List<PsiElement> {
        if (qualifiedName == CommonClassNames.JAVA_LANG_OBJECT) {
            // Every class on the JVM is a subclass of `java.lang.Object`.
            return emptyList()
        }
        if (this is PsiAnonymousClass || hasModifierProperty(PsiModifier.FINAL)) return emptyList()
        val scope = useScope
        if (isAnnotationType) {
            return AnnotatedElementsSearch.searchPsiClasses(this, scope).findAll()
                .filter { it.isAnnotationType }
                .mapNotNull { it.unwrapped }
        }
        val inheritors = ClassInheritorsSearch.search(this, scope, false).findAll().mapNotNull { it.unwrapped }
        if (!isInterface || !LambdaUtil.isFunctionalClass(this)) return inheritors
        return inheritors + FunctionalExpressionSearch.search(this, scope).findAll()
    }

    /**
     * Finds the annotation classes that are annotated with this annotation class. Kotlin ones come from the
     * annotations index, Java ones from [AnnotatedElementsSearch] over the light class.
     */
    private fun KtClass.annotatedAnnotationClasses(scope: SearchScope): List<PsiElement> {
        val project = project
        val javaAnnotations = toLightClass()?.let { lightClass ->
            AnnotatedElementsSearch.searchPsiClasses(lightClass, scope.excludeKotlinSources(project)).findAll().filter { it.isAnnotationType }
        }.orEmpty()
        val name = name ?: return javaAnnotations
        val candidates = when (scope) {
            is GlobalSearchScope -> KotlinAnnotationsIndex[name, project, KotlinSourceFilterScope.everything(scope, project)]
            is LocalSearchScope -> scope.scope.flatMap { it.collectDescendantsOfType<KtAnnotationEntry>() }
            else -> emptyList()
        }
        val kotlinAnnotations = candidates.mapNotNull { entry ->
            entry.getStrictParentOfType<KtClass>()?.takeIf { owner ->
                owner.isAnnotation()
                && owner.annotationEntries.contains(entry)
                && entry.annotationClass() == this
            }
        }
        return kotlinAnnotations + javaAnnotations
    }

    /** The annotation class of this entry. The reference resolves to the constructor when the class declares one. */
    private fun KtAnnotationEntry.annotationClass(): PsiElement? {
        val resolved = calleeExpression?.constructorReferenceExpression?.mainReference?.resolve() ?: return null
        return if (resolved is KtConstructor<*>) resolved.getContainingClassOrObject() else resolved
    }

    private fun PsiElement.isInterfaceElement(): Boolean =
        this is KtClass && isInterface() || this is PsiClass && isInterface

    private fun PsiElement.toTypeHierarchyItem(): TypeHierarchyItem? = when (this) {
        is KtClassOrObject -> toTypeHierarchyItem()
        is PsiClass -> toTypeHierarchyItem()
        is PsiFunctionalExpression -> toTypeHierarchyItem()
        else -> null
    }

    private fun KtClassOrObject.toTypeHierarchyItem(): TypeHierarchyItem? {
        val navigationClass = navigationElement as? KtClassOrObject ?: this
        val location = navigationClass.itemLocation() ?: return null
        val name = navigationClass.presentableName()
        val fqName = navigationClass.fqName?.asString()
        return TypeHierarchyItem(
            name = name,
            kind = navigationClass.getKind() ?: SymbolKind.Class,
            tags = if (navigationClass.isDeprecated()) listOf(SymbolTag.Deprecated) else null,
            detail = fqName?.takeIf { it != name },
            uri = location.uri,
            range = location.range,
            selectionRange = navigationClass.nameIdentifier?.textRange?.toLspRange(location.document) ?: location.range,
            data = navigationClass.itemData(location),
        )
    }

    private fun PsiClass.toTypeHierarchyItem(): TypeHierarchyItem? {
        val navigationClass = navigationElement as? PsiClass ?: this
        val location = navigationClass.itemLocation() ?: return null
        val name = ClassPresentationUtil.getNameForClass(navigationClass, false) ?: "<invalid name>"
        return TypeHierarchyItem(
            name = name,
            kind = when {
                navigationClass.isInterface -> SymbolKind.Interface
                navigationClass.isEnum -> SymbolKind.Enum
                navigationClass.isRecord -> SymbolKind.Struct
                else -> SymbolKind.Class
            },
            tags = if (navigationClass.isDeprecated) listOf(SymbolTag.Deprecated) else null,
            detail = navigationClass.qualifiedName?.takeIf { it != name },
            uri = location.uri,
            range = location.range,
            selectionRange = navigationClass.nameIdentifier?.textRange?.toLspRange(location.document) ?: location.range,
            data = navigationClass.itemData(location),
        )
    }

    private fun PsiFunctionalExpression.toTypeHierarchyItem(): TypeHierarchyItem? {
        val location = itemLocation() ?: return null
        // A functional expression is always a leaf of the hierarchy.
        return TypeHierarchyItem(
            name = ClassPresentationUtil.getFunctionalExpressionPresentation(this, false),
            kind = SymbolKind.Function,
            tags = if (this is PsiDocCommentOwner && isDeprecated) listOf(SymbolTag.Deprecated) else null,
            detail = null,
            uri = location.uri,
            range = location.range,
            selectionRange = location.range,
            data = null,
        )
    }

    private class ItemLocation(
        val uri: DocumentUri,
        val range: Range,
        val document: Document,
        val virtualFile: VirtualFile,
    )

    private fun PsiElement.itemLocation(): ItemLocation? {
        val containingFile = containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null
        val textRange = textRange ?: return null
        return ItemLocation(DocumentUri(virtualFile.uri), textRange.toLspRange(document), document, virtualFile)
    }

    private fun PsiElement.itemData(location: ItemLocation): JsonElement {
        val compiledClassName = if (this is PsiCompiledElement && this is PsiClass) qualifiedName else null
        val data = if (compiledClassName != null) {
            TypeHierarchyItemData(qualifiedName = compiledClassName, configurationEntryId = uniqueId)
        }
        else {
            TypeHierarchyItemData(pointer = PsiSerializablePointer.create(this, location.virtualFile), configurationEntryId = uniqueId)
        }
        return LSP.json.encodeToJsonElement(data)
    }

    private fun TypeHierarchyItemData.restore(project: Project): PsiElement? {
        val qualifiedName = qualifiedName
        if (qualifiedName != null) {
            return JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project))
        }
        return pointer?.restore(project)
    }

    private fun KtClassOrObject.presentableName(): String {
        name?.let { return it }
        val containers = generateSequence(parent) { it.parent }
            .filterIsInstance<KtNamedDeclaration>()
            .mapNotNull { it.name }
            .take(2)
            .toList()
        if (containers.isEmpty()) return JavaPsiBundle.message("anonymous.class.display")
        val context = containers.reduce { inner, outer -> JavaPsiBundle.message("class.context.display", inner, outer) }
        return JavaPsiBundle.message("anonymous.class.context.display", context)
    }

    private fun KtClassOrObject.isDeprecated(): Boolean =
        annotationEntries.any { it.shortName?.asString() == "Deprecated" }

    /**
     * A source node carries a [pointer]. A compiled class carries its [qualifiedName], because its PSI offsets
     * point into the decompiled mirror and cannot restore the compiled element.
     */
    @Serializable
    private data class TypeHierarchyItemData(
        val pointer: PsiSerializablePointer? = null,
        val qualifiedName: String? = null,
        override val configurationEntryId: LSUniqueConfigurationEntry.UniqueId,
    ) : ResolveDataWithConfigurationEntryId {
        companion object {
            fun fromJson(jsonElement: JsonElement?): TypeHierarchyItemData? {
                if (jsonElement == null) return null
                return try {
                    LSP.json.decodeFromJsonElement(serializer(), jsonElement)
                }
                catch (_: Exception) {
                    null
                }
            }
        }
    }
}
