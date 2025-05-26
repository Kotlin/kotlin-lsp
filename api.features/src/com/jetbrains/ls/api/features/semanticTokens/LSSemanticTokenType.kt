// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.semanticTokens

interface LSSemanticTokenType {
    val name: String
}

class LSSemanticTokenTypeCustom(
    override val name: String
) : LSSemanticTokenType {
    override fun toString(): String = name
    override fun equals(other: Any?): Boolean = this === other || other is LSSemanticTokenType && other.name == name
    override fun hashCode(): Int = name.hashCode()
}

class LSSemanticTokenTypePredefined private constructor(
    override val name: String
) : LSSemanticTokenType {
    override fun toString(): String = name
    override fun equals(other: Any?): Boolean = this === other || other is LSSemanticTokenType && other.name == name
    override fun hashCode(): Int = name.hashCode()

    companion object {
        private val all: MutableList<LSSemanticTokenTypePredefined> = mutableListOf()

        private fun predefined(name: String): LSSemanticTokenTypePredefined {
            val token = LSSemanticTokenTypePredefined(name)
            all.add(token)
            return token
        }

        /** For identifiers that declare or reference a namespace, module, or package. */
        val NAMESPACE: LSSemanticTokenTypePredefined = predefined("namespace")

        /** For identifiers that declare or reference a class type. */
        val CLASS: LSSemanticTokenTypePredefined = predefined("class")

        /** For identifiers that declare or reference an enumeration type. */
        val ENUM: LSSemanticTokenTypePredefined = predefined("enum")

        /** For identifiers that declare or reference an interface type. */
        val INTERFACE: LSSemanticTokenTypePredefined = predefined("interface")

        /** For identifiers that declare or reference a struct type. */
        val STRUCT: LSSemanticTokenTypePredefined = predefined("struct")

        /** For identifiers that declare or reference a type parameter. */
        val TYPE_PARAMETER: LSSemanticTokenTypePredefined = predefined("typeParameter")

        /** For identifiers that declare or reference a type that is not covered above. */
        val TYPE: LSSemanticTokenTypePredefined = predefined("type")

        /** For identifiers that declare or reference a function or method parameters. */
        val PARAMETER: LSSemanticTokenTypePredefined = predefined("parameter")

        /** For identifiers that declare or reference a local or global variable. */
        val VARIABLE: LSSemanticTokenTypePredefined = predefined("variable")

        /** For identifiers that declare or reference a member property, member field, or member variable. */
        val PROPERTY: LSSemanticTokenTypePredefined = predefined("property")

        /** For identifiers that declare or reference an enumeration property, constant, or member. */
        val ENUM_MEMBER: LSSemanticTokenTypePredefined = predefined("enumMember")

        /**  For identifiers that declare an event property. */
        val EVENT: LSSemanticTokenTypePredefined = predefined("event")

        /** For identifiers that declare a function. */
        val FUNCTION: LSSemanticTokenTypePredefined = predefined("function")

        /**  For identifiers that declare a member function or method. */
        val METHOD: LSSemanticTokenTypePredefined = predefined("method")

        /** For identifiers that declare a macro. */
        val MACRO: LSSemanticTokenTypePredefined = predefined("macro")

        /** For tokens that represent a language keyword. */
        val KEYWORD: LSSemanticTokenTypePredefined = predefined("keyword")

        val MODIFIER: LSSemanticTokenTypePredefined = predefined("modifier")

        /** For tokens that represent a comment. */
        val COMMENT: LSSemanticTokenTypePredefined = predefined("comment")

        /** For tokens that represent a string literal. */
        val STRING: LSSemanticTokenTypePredefined = predefined("string")

        /** For tokens that represent a number literal. */
        val NUMBER: LSSemanticTokenTypePredefined = predefined("number")

        /** For tokens that represent a regular expression literal. */
        val REGEXP: LSSemanticTokenTypePredefined = predefined("regexp")

        /** For tokens that represent an operator. */
        val OPERATOR: LSSemanticTokenTypePredefined = predefined("operator")

        /**
         * For identifiers that declare or reference decorators and annotations.
         *
         * @since 3.17.0
         */
        val DECORATOR: LSSemanticTokenTypePredefined = predefined("decorator")

        val ALL: List<LSSemanticTokenTypePredefined> = all.toList()

        init {
            all.clear()
        }
    }
}