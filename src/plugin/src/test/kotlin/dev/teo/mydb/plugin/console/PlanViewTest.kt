package dev.teo.mydb.plugin.console

import dev.teo.mydb.core.plan.QueryPlans
import dev.teo.mydb.plugin.MyDbTestCase

/** Query plan tab rendering:. */
class PlanViewTest : MyDbTestCase() {
    fun `test plan tree shows readable operations fully expanded`() {
        val handle = ConsoleService.getInstance(project).session("plan-test", "plan-test", dataSource)
        try {
            val plan = QueryPlans.explain(handle.session,
                "select c.name, count(*) from shop.orders o join shop.customers c on c.id = o.customer_id " +
                    "join shop.order_items i on i.order_id = o.id where o.status = 'paid' group by c.name", false)
            val view = PlanView(project, plan)
            val ops = view.shownOperations()
            assertEquals("Select", ops.first())
            assertEquals("every node is expanded", count(plan.root), ops.size)
            assertTrue(ops.toString(), ops.none { it.startsWith("PlanNode(") })
            assertTrue(ops.toString(), ops.any { it.startsWith("Full Scan") })
            assertTrue(view.asText(), view.asText().lines().first().startsWith("Select"))
            // the diagram fits a small view instead of opening at 1:1
            val d = dev.teo.mydb.core.diagram.Diagrams.plan(plan.root)
            dev.teo.mydb.core.diagram.LayeredLayout.apply(d, dev.teo.mydb.core.diagram.LayeredLayout.Direction.LEFT_RIGHT)
            val canvas = dev.teo.mydb.plugin.diagram.DiagramCanvas(d)
            canvas.fit(600, 300)
            assertTrue("scale ${canvas.currentScale}", canvas.currentScale < 1.0)
            assertTrue(canvas.preferredSize.width <= 600)
            val editor = dev.teo.mydb.plugin.diagram.DiagramEditor(project, dev.teo.mydb.plugin.diagram.DiagramFile("plan", d, dev.teo.mydb.core.diagram.LayeredLayout.Direction.LEFT_RIGHT))
            assertTrue("context menu on the canvas", editor.canvas.mouseListeners.any { it is com.intellij.ui.PopupHandler })
            assertTrue((0 until view.table.columnCount).map { view.table.getColumnName(it) }.contains("Raw description"))
        } finally { ConsoleService.getInstance(project).close("plan-test") }
    }

    fun `test plan toolbar reruns, hides the raw column and the diagram is a compact tree with properties`() {
        val handle = ConsoleService.getInstance(project).session("plan-test2", "plan-test2", dataSource)
        try {
            val plan = QueryPlans.explain(handle.session,
                "select c.name, count(*) from shop.orders o join shop.customers c on c.id = o.customer_id " +
                    "join shop.order_items i on i.order_id = o.id where o.status = 'paid' group by c.name", false)
            var reruns = 0
            val view = PlanView(project, plan) { reruns++ }
            val toolbar = com.intellij.util.ui.UIUtil.findComponentsOfType(view, com.intellij.openapi.actionSystem.impl.ActionToolbarImpl::class.java).single()
            val run = toolbar.actionGroup.getChildren(null).first { it.templateText == "Run Again" }
            run.actionPerformed(com.intellij.testFramework.TestActionEvent.createTestEvent(run))
            assertEquals(1, reruns)
            view.showRawColumn(false)
            assertFalse((0 until view.table.columnCount).map { view.table.getColumnName(it) }.contains("Raw description"))
            view.showRawColumn(true)
            assertTrue((0 until view.table.columnCount).map { view.table.getColumnName(it) }.contains("Raw description"))

            // tidy tree: root on the left, children to its right, no overlapping cards, parent centred on its children
            val d = dev.teo.mydb.core.diagram.Diagrams.plan(plan.root)
            dev.teo.mydb.core.diagram.LayeredLayout.applyTree(d, "0")
            assertEquals(0.0, d.node("0").x)
            d.edges.forEach { e -> assertTrue("$e", d.node(e.from).x > d.node(e.to).x) }
            for (a in d.nodes) for (b2 in d.nodes) if (a !== b2) {
                val overlap = a.x < b2.x + b2.width && b2.x < a.x + a.width && a.y < b2.y + b2.height && b2.y < a.y + a.height
                assertFalse("${a.id} overlaps ${b2.id}", overlap)
            }
            val height = d.nodes.maxOf { it.y + it.height }
            val leaves = d.nodes.count { n -> d.edges.none { it.to == n.id } }
            assertTrue("height $height for $leaves leaves", height <= leaves * (d.nodes.maxOf { it.height } + 24.0))

            val editor = dev.teo.mydb.plugin.diagram.DiagramEditor(project, dev.teo.mydb.plugin.diagram.DiagramFile("plan", d, dev.teo.mydb.core.diagram.LayeredLayout.Direction.LEFT_RIGHT))
            val scan = d.nodes.first { it.title.startsWith("Full Scan") }
            editor.canvas.select(scan.id)
            val model = editor.propertiesModel!!
            assertTrue((0 until model.rowCount).any { model.getValueAt(it, 0) == "Node Type" && model.getValueAt(it, 1) == "Seq Scan" })
        } finally { ConsoleService.getInstance(project).close("plan-test2") }
    }

    private fun count(n: dev.teo.mydb.core.plan.PlanNode): Int = 1 + n.children.sumOf { count(it) }
}
