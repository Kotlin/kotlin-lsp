package com.jetbrains.ls.api.features.impl.kotlin.psi

import com.intellij.navigation.ChooseByNameContributorEx2
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.idea.goto.KotlinGotoPropertySymbolContributor
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.java.getPropertyNamesCandidatesByAccessorName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeAsciiOnly

/**
 * Finds a Kotlin property by its JVM accessor name in a Copy Reference query, e.g. `pkg.Cls#getUseSplash` for `var useSplash`.
 * Kotlin hides its light accessors from goto, so no other contributor gives these names.
 *
 * Only a query with `#` whose name part is a getter or a setter name gets names, see [JvmAbi.isGetterName] and [JvmAbi.isSetterName].
 * The names follow [JvmAbi.getterName] and [JvmAbi.setterName]: an `isX` getter is the property name, so
 * [KotlinGotoPropertySymbolContributor] finds it. `@JvmName`, `@JvmField`, `const` and private properties are not special.
 * The items are [Accessor]s, so the qualified name and the symbol name have the accessor, e.g. `pkg.Cls.getUseSplash`.
 */
internal class LSKotlinPropertyAccessorContributor : ChooseByNameContributorEx2, GotoClassContributor {
    private val properties = KotlinGotoPropertySymbolContributor()

    override fun processNames(processor: Processor<in String>, parameters: FindSymbolParameters) {
        val name = parameters.localPatternName
        if ('#' !in parameters.completePattern) return
        val accessorName: (String) -> String = when {
            JvmAbi.isSetterName(name) -> JvmAbi::setterName
            JvmAbi.isGetterName(name) -> JvmAbi::getterName
            else -> return
        }
        properties.processNames(
            { propertyName -> accessorName(propertyName).let { it == propertyName || processor.process(it) } },
            parameters.searchScope,
            parameters.idFilter,
        )
    }

    /** No names without the query, see the other [processNames]. */
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        if (!Name.isValidIdentifier(name)) return
        for (propertyName in propertyNameCandidates(name)) {
            var proceed = true
            properties.processElementsWithName(propertyName, { item ->
                val accessor = (item as? KtNamedDeclaration)?.let { accessor(it, name) }
                accessor == null || processor.process(accessor).also { proceed = it }
            }, parameters)
            if (!proceed) return
        }
    }

    /**
     * The property names that may have the accessor [accessorName]; [accessor] keeps the right ones.
     * [getPropertyNamesCandidatesByAccessorName] lowercases the whole leading capitals (`getURL` -> `url`), but [JvmAbi.getterName]
     * and [JvmAbi.setterName] capitalize only the first char, so `getURL` is also `URL` and `uRL`.
     */
    private fun propertyNameCandidates(accessorName: String): Set<String> {
        val names = getPropertyNamesCandidatesByAccessorName(Name.identifier(accessorName)).mapTo(LinkedHashSet()) { it.asString() }
        if (accessorName.length > 3 && (accessorName.startsWith("get") || accessorName.startsWith("set"))) {
            val name = accessorName.substring(3)
            names += name
            names += name.decapitalizeAsciiOnly()
        }
        return names
    }

    override fun getQualifiedName(item: NavigationItem): String? = (item as? Accessor)?.qualifiedName

    override fun getQualifiedNameSeparator(): String = "."

    /** A property item named by its accessor [name], e.g. `getUseSplash`, and [qualifiedName] by the JVM class of the accessor. */
    class Accessor(val target: KtNamedDeclaration, private val name: String, val qualifiedName: String) : PsiElementNavigationItem {
        override fun getTargetElement(): PsiElement = target
        override fun getName(): String = name
        override fun getPresentation(): ItemPresentation? = target.presentation
        override fun canNavigate(): Boolean = target.canNavigate()
        override fun navigate(requestFocus: Boolean): Unit = target.navigate(requestFocus)
    }

    /** [declaration] as an [Accessor] when its getter, or the setter of a `var`, is [accessorName]. */
    private fun accessor(declaration: KtNamedDeclaration, accessorName: String): Accessor? {
        val name = declaration.name ?: return null
        val isVar = when (declaration) {
            is KtProperty -> declaration.isVar
            is KtParameter -> declaration.isMutable
            else -> return null
        }
        if (accessorName != JvmAbi.getterName(name) && !(isVar && accessorName == JvmAbi.setterName(name))) return null
        val container = accessorContainerName(declaration) ?: return null
        return Accessor(declaration, accessorName, "$container.$accessorName")
    }

    /**
     * The JVM class of the accessors: the class or object, the file facade for a top-level property.
     * `null` without accessors in a named class, e.g. an annotation class parameter or an object literal member.
     */
    private fun accessorContainerName(declaration: KtNamedDeclaration): String? = when (declaration) {
        is KtProperty -> {
            if (declaration.isTopLevel) declaration.containingKtFile.javaFileFacadeFqName.asString()
            else declaration.containingClassOrObject?.fqName?.asString()
        }
        is KtParameter -> (declaration.ownerFunction as? KtPrimaryConstructor)?.getContainingClassOrObject()
            ?.takeUnless { it is KtClass && it.isAnnotation() }?.fqName?.asString()
        else -> null
    }
}
