// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import org.jdom.Text

internal fun parseXml(xmlString: String): XmlElement {
    val root = JDOMUtil.load(xmlString)
    return parseElement(root)
}

private fun parseElement(element: Element): XmlElement {
    val attributes = buildMap {
        element.attributes.forEach { attr -> put(attr.name, attr.value) }
    }
        .takeIf { it.isNotEmpty() } ?: emptyMap()

    val children = buildList {
        element.children.forEach {
            add(parseElement(it))
        }
    }
        .takeIf { it.isNotEmpty() } ?: emptyList()

    val text = element.textTrim.takeIf { it.isNotEmpty() }

    return XmlElement(
        tag = element.name,
        attributes = attributes,
        children = children,
        text = text
    )
}


internal fun toXml(xmlElement: XmlElement): String {
    val element = toJDomElement(xmlElement)
    return JDOMUtil.writeElement(element)
}

private fun toJDomElement(xmlElement: XmlElement): Element {
    val element = Element(xmlElement.tag)
    xmlElement.attributes.forEach { (key, value) ->
        element.setAttribute(key, value)
    }
    xmlElement.children.forEach { child ->
        element.addContent(toJDomElement(child))
    }
    xmlElement.text?.let {
        element.addContent(Text(it))
    }
    return element
}
