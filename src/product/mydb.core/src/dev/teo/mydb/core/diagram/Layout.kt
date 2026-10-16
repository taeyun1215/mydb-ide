package dev.teo.mydb.core.diagram

/**
 * Layered (Sugiyama-style) layout: longest-path layering along edges, barycenter crossing
 * reduction, then coordinates per layer. For ERDs parents (edge targets) go above children;
 * for plans the layers run left to right.
 */
object LayeredLayout {
    /**
     * Tidy tree layout for plan diagrams: the root on the left, each leaf on its own row and every parent
     * centred on its children, so branches fold above and below instead of stretching one line.
     */
    fun applyTree(d: Diagram, root: String, hGap: Double = 60.0, vGap: Double = 24.0) {
        val children = d.edges.groupBy({ it.to }, { it.from })
        val depthWidth = HashMap<Int, Double>()
        fun measure(id: String, depth: Int) {
            depthWidth[depth] = maxOf(depthWidth[depth] ?: 0.0, d.node(id).width)
            children[id].orEmpty().forEach { measure(it, depth + 1) }
        }
        measure(root, 0)
        val xAt = (0..(depthWidth.keys.maxOrNull() ?: 0)).runningFold(0.0) { acc, depth -> acc + (depthWidth[depth] ?: 0.0) + hGap }
        var nextY = 0.0
        fun place(id: String, depth: Int): Double {
            val n = d.node(id)
            n.x = xAt[depth]
            val kids = children[id].orEmpty()
            if (kids.isEmpty()) { n.y = nextY; nextY += n.height + vGap; return n.y + n.height / 2 }
            val centres = kids.map { place(it, depth + 1) }
            n.y = (centres.first() + centres.last()) / 2 - n.height / 2
            return centres.let { (it.first() + it.last()) / 2 }
        }
        place(root, 0)
        // a parent centred above tall children may start above 0: shift everything down
        val minY = d.nodes.minOfOrNull { it.y } ?: 0.0
        if (minY < 0) d.nodes.forEach { it.y -= minY }
    }

    enum class Direction { TOP_DOWN, LEFT_RIGHT }

    fun apply(d: Diagram, direction: Direction = Direction.TOP_DOWN, gap: Double = 60.0) {
        val ids = d.nodes.map { it.id }
        val parents = d.edges.groupBy({ it.from }, { it.to }) // child -> parents
        val layer = HashMap<String, Int>()
        val visiting = HashSet<String>()
        fun depth(id: String): Int {
            layer[id]?.let { return it }
            if (!visiting.add(id)) return 0 // cycle: break
            val v = (parents[id].orEmpty().filter { it != id }.maxOfOrNull { depth(it) + 1 }) ?: 0
            visiting.remove(id)
            layer[id] = v
            return v
        }
        ids.forEach(::depth)

        // isolated nodes (no edges) go to an extra last layer so they don't clutter the hierarchy
        val connected = d.edges.flatMap { listOf(it.from, it.to) }.toSet()
        val maxLayer = layer.values.maxOrNull() ?: 0
        ids.filter { it !in connected }.forEach { layer[it] = maxLayer + 1 }

        val layers = ids.groupBy { layer[it]!! }.toSortedMap().values.map { it.toMutableList() }
        // barycenter ordering, a few sweeps
        val neighbours = d.edges.flatMap { listOf(it.from to it.to, it.to to it.from) }.groupBy({ it.first }, { it.second })
        repeat(4) {
            for (i in 1 until layers.size) {
                val pos = layers[i - 1].withIndex().associate { it.value to it.index.toDouble() }
                layers[i].sortBy { id -> neighbours[id].orEmpty().mapNotNull { pos[it] }.average().takeIf { !it.isNaN() } ?: Double.MAX_VALUE }
            }
        }
        var offset = 0.0
        for (l in layers) {
            val nodes = l.map { d.node(it) }
            var cross = 0.0
            for (n in nodes) {
                if (direction == Direction.TOP_DOWN) { n.x = cross; n.y = offset; cross += n.width + gap }
                else { n.x = offset; n.y = cross; cross += n.height + gap }
            }
            offset += (if (direction == Direction.TOP_DOWN) nodes.maxOf { it.height } else nodes.maxOf { it.width }) + gap * 1.5
        }
    }
}
