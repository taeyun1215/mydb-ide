package dev.teo.mydb.core.diagram

/** Text exports of a diagram. */
object DiagramExport {
    private fun id(s: String) = s.replace(Regex("[^A-Za-z0-9_]"), "_")

    fun mermaid(d: Diagram): String = buildString {
        appendLine("erDiagram")
        for (n in d.nodes) {
            appendLine("    ${id(n.id)} {")
            n.rows.forEach { (icon, name, type) ->
                val key = when (icon) { "pk" -> " PK"; "fk" -> " FK"; "pk-fk" -> " PK, FK"; else -> "" }
                appendLine("        ${id(type.substringBefore('('))} ${id(name)}$key")
            }
            appendLine("    }")
        }
        d.visibleEdges.forEach { e ->
            // a view reading a table is a dotted, non-identifying relation
            if (e.kind == EdgeKind.VIEW) appendLine("    ${id(e.to)} }o..o{ ${id(e.from)} : \"view\"")
            else appendLine("    ${id(e.to)} ||--o{ ${id(e.from)} : \"${e.label}\"")
        }
    }

    fun plantUml(d: Diagram): String = buildString {
        appendLine("@startuml")
        for (n in d.nodes) {
            appendLine("entity ${id(n.id)} {")
            n.rows.forEach { (icon, name, type) -> appendLine("  ${if (icon.startsWith("pk")) "* " else ""}$name : $type") }
            appendLine("}")
        }
        d.visibleEdges.forEach { e -> appendLine("${id(e.from)} }o--|| ${id(e.to)} : ${e.label}") }
        appendLine("@enduml")
    }

    fun dot(d: Diagram, withPositions: Boolean = false): String = buildString {
        appendLine("digraph ERD {")
        appendLine("  node [shape=record];")
        for (n in d.nodes) {
            val label = (listOf(n.title) + n.rows.map { "${it.second}: ${it.third}" }).joinToString("|") { it.replace("|", "\\|").replace("{", "\\{").replace("}", "\\}") }
            val pos = if (withPositions) ", pos=\"${n.x.toInt()},${(-n.y).toInt()}!\"" else ""
            appendLine("  ${id(n.id)} [label=\"{$label}\"$pos];")
        }
        d.visibleEdges.forEach { e -> appendLine("  ${id(e.from)} -> ${id(e.to)} [label=\"${e.label}\"];") }
        appendLine("}")
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Plain GraphML (opens in yEd and other graph tools): node label = title and rows, positions as data. */
    fun graphml(d: Diagram): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<graphml xmlns="http://graphml.graphdrawing.org/xmlns">""")
        appendLine("""  <key id="label" for="all" attr.name="label" attr.type="string"/>""")
        appendLine("""  <key id="x" for="node" attr.name="x" attr.type="double"/>""")
        appendLine("""  <key id="y" for="node" attr.name="y" attr.type="double"/>""")
        appendLine("""  <graph id="G" edgedefault="directed">""")
        for (n in d.nodes) {
            val label = (listOf(n.title) + n.rows.map { "${it.second}: ${it.third}" }).joinToString("\n")
            appendLine("""    <node id="${xml(n.id)}"><data key="label">${xml(label)}</data><data key="x">${n.x}</data><data key="y">${n.y}</data></node>""")
        }
        d.visibleEdges.forEachIndexed { i, e -> appendLine("""    <edge id="e$i" source="${xml(e.from)}" target="${xml(e.to)}"><data key="label">${xml(e.label)}</data></edge>""") }
        appendLine("  </graph>")
        appendLine("</graphml>")
    }

    /** draw.io (diagrams.net) file: one rectangle per node at its layout position, one edge per relation. */
    fun drawio(d: Diagram): String = buildString {
        appendLine("""<mxfile><diagram name="Page-1"><mxGraphModel><root>""")
        appendLine("""<mxCell id="0"/><mxCell id="1" parent="0"/>""")
        for (n in d.nodes) {
            val label = (listOf("<b>${xml(n.title)}</b>") + n.rows.map { xml("${it.second}  ${it.third}") }).joinToString("<br>")
            appendLine("""<mxCell id="${xml(n.id)}" value="${xml(label)}" style="rounded=0;whiteSpace=wrap;html=1;align=left;verticalAlign=top;spacingLeft=4;" vertex="1" parent="1">""" +
                """<mxGeometry x="${n.x}" y="${n.y}" width="${n.width}" height="${n.height}" as="geometry"/></mxCell>""")
        }
        d.visibleEdges.forEachIndexed { i, e ->
            appendLine("""<mxCell id="edge$i" value="${xml(e.label)}" style="endArrow=block;html=1;" edge="1" parent="1" source="${xml(e.from)}" target="${xml(e.to)}"><mxGeometry relative="1" as="geometry"/></mxCell>""")
        }
        appendLine("""</root></mxGraphModel></diagram></mxfile>""")
    }

    /** SVG rendering with the current layout; used for image export. */
    fun svg(d: Diagram): String {
        val w = (d.nodes.maxOfOrNull { it.x + it.width } ?: 0.0) + 20
        val h = (d.nodes.maxOfOrNull { it.y + it.height } ?: 0.0) + 20
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return buildString {
            appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="${w.toInt()}" height="${h.toInt()}" font-family="monospace" font-size="12">""")
            for (e in d.visibleEdges) {
                val a = d.node(e.from); val b = d.node(e.to)
                appendLine("""  <line x1="${a.x + a.width / 2 + 10}" y1="${a.y + 10}" x2="${b.x + b.width / 2 + 10}" y2="${b.y + b.height + 10}" stroke="#888"/>""")
            }
            for (n in d.nodes) {
                appendLine("""  <g transform="translate(${n.x + 10},${n.y + 10})"><rect width="${n.width}" height="${n.height}" fill="#fff" stroke="#444"/>""")
                appendLine("""    <text x="8" y="18" font-weight="bold">${esc(n.title)}</text>""")
                n.rows.forEachIndexed { i, r -> appendLine("""    <text x="8" y="${40 + 22 * i}">${esc(r.second)} ${esc(r.third)}</text>""") }
                appendLine("  </g>")
            }
            appendLine("</svg>")
        }
    }
}
