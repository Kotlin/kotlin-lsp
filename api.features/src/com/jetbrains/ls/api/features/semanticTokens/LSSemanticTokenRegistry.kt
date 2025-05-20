package com.jetbrains.ls.api.features.semanticTokens

class LSSemanticTokenRegistry(
    val types: List<LSSemanticTokenType>,
    val modifiers: List<LSSemanticTokenModifier>,
) {

    init {
        requireUnique(types)
        requireUnique(modifiers)
    }

    private fun <T> requireUnique(list: List<T>) {
        val set = mutableSetOf<T>()
        for (element in list) {
            require(!set.contains(element)) {
                "Duplicate value $element in list: $list"
            }
            set.add(element)
        }
    }

    private val typeToIndex: Map<LSSemanticTokenType, Int> =
        types.withIndex().associate { it.value to it.index }

    private val modifierToIndex: Map<LSSemanticTokenModifier, Int> =
        modifiers.withIndex().associate { it.value to it.index }

    fun typeToIndex(type: LSSemanticTokenType): Int {
        return typeToIndex[type]
            ?: error("Unregistered semantic token type: $type")
    }

    fun indexToType(index: Int): LSSemanticTokenType {
        return types[index]
    }

    fun modifierToIndex(modifier: LSSemanticTokenModifier): Int {
        return modifierToIndex[modifier]
            ?: error("Unregistered semantic token modifier: $modifier")
    }

    fun indexToModifier(index: Int): LSSemanticTokenModifier {
        return modifiers[index]
    }

    companion object {
        val EMPTY: LSSemanticTokenRegistry = LSSemanticTokenRegistry(
            types = emptyList(),
            modifiers = emptyList(),
        )
    }
}