package com.jetbrains.ls.imports.json

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal fun parseXml(xmlString: String): XmlElement {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val inputSource = InputSource(StringReader(xmlString))
        val document = builder.parse(inputSource)
        val root = document.documentElement
        return parseElement(root, xmlString)
}

private fun parseElement(element: Element, xmlString: String): XmlElement {
    val attributeNodes = mutableListOf<Node>()
    for (i in 0 until element.attributes.length) {
        val attr = element.attributes.item(i)
        attributeNodes.add(attr)
    }

    // An attempt to preserve the original attribute order for better readability. Not very reliable, but works in most cases.
    attributeNodes.sortBy { xmlString.indexOf(it.nodeName) }

    val children = mutableListOf<XmlElement>()
    val childNodes = element.childNodes
    for (i in 0 until childNodes.length) {
        val childNode = childNodes.item(i)
        if (childNode.nodeType == Node.ELEMENT_NODE) {
            children.add(parseElement(childNode as Element, xmlString))
        }
    }

    val text = element.textContent.trim().takeIf { it.isNotEmpty() }

    return XmlElement(
        tag = element.tagName,
        attributes = attributeNodes.associate { it.nodeName to it.nodeValue },
        children = children,
        text = text
    )
}


internal fun toXml(xmlElement: XmlElement): String {
    val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val document = documentBuilder.newDocument()
    val rootElement = buildElement(document, xmlElement)
    document.appendChild(rootElement)
    val writer = StringWriter()
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
    transformer.transform(DOMSource(document), StreamResult(writer))
    return writer.toString()
}

private fun buildElement(document: Document, xmlElement: XmlElement): Element {
    val element = document.createElement(xmlElement.tag)
    xmlElement.attributes.forEach { (key, value) ->
        element.setAttribute(key, value)
    }
    xmlElement.children.forEach { child ->
        val childElement = buildElement(document, child)
        element.appendChild(childElement)
    }
    xmlElement.text?.let {
        element.appendChild(document.createTextNode(it))
    }
    return element
}
