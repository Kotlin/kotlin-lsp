package com.jetbrains.ls.api.features.semanticTokens

interface LSSemanticTokenModifier {
    val name: String
}

class LSSemanticTokenModifierCustom(
    override val name: String
) : LSSemanticTokenModifier {
    override fun toString(): String = name
    override fun equals(other: Any?): Boolean = this === other || other is LSSemanticTokenModifier && other.name == name
    override fun hashCode(): Int = name.hashCode()
}

class LSSemanticTokenModifierPredefined private constructor(
    override val name: String
) : LSSemanticTokenModifier {
    override fun toString(): String = name
    override fun equals(other: Any?): Boolean = this === other || other is LSSemanticTokenModifier && other.name == name
    override fun hashCode(): Int = name.hashCode()

    companion object {
        private val all: MutableList<LSSemanticTokenModifierPredefined> = mutableListOf()

        private fun predefined(name: String): LSSemanticTokenModifierPredefined {
            val token = LSSemanticTokenModifierPredefined(name)
            all.add(token)
            return token
        }

        /** For declarations of symbols. */
        val DECLARATION: LSSemanticTokenModifierPredefined = predefined("declaration")

        /** For definitions of symbols, for example, in header files.  */
        val DEFINITION: LSSemanticTokenModifierPredefined = predefined("definition")

        /** For readonly variables and member fields (constants). */
        val READONLY: LSSemanticTokenModifierPredefined = predefined("readonly")

        /** For class members (static members). */
        val STATIC: LSSemanticTokenModifierPredefined = predefined("static")

        /** For symbols that should no longer be used. */
        val DEPRECATED: LSSemanticTokenModifierPredefined = predefined("deprecated")

        /** For types and member functions that are abstract. */
        val ABSTRACT: LSSemanticTokenModifierPredefined = predefined("abstract")

        /** For functions that are marked async. */
        val ASYNC: LSSemanticTokenModifierPredefined = predefined("async")

        /** For variable references where the variable is assigned to. */
        val MODIFICATION: LSSemanticTokenModifierPredefined = predefined("modification")

        /** For occurrences of symbols in documentation. */
        val DOCUMENTATION: LSSemanticTokenModifierPredefined = predefined("documentation")

        /** For symbols that are part of the standard library. */
        val DEFAULT_LIBRARY: LSSemanticTokenModifierPredefined = predefined("defaultLibrary")

        val ALL: List<LSSemanticTokenModifierPredefined> = all.toList()

        init {
            all.clear()
        }
    }
}