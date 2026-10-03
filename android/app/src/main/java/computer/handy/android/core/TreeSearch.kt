package computer.handy.android.core

/** Bounded breadth-first search, used to find the focused field inside virtual view trees. */
object TreeSearch {

    /**
     * @param limit maximum number of nodes visited (web pages can expose thousands)
     * @return the first node, closest to [root], matching [predicate]
     */
    fun <T> findFirst(root: T, children: (T) -> List<T>, limit: Int, predicate: (T) -> Boolean): T? {
        val queue = ArrayDeque<T>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val node = queue.removeFirst()
            visited++
            if (predicate(node)) return node
            children(node).forEach { queue.addLast(it) }
        }
        return null
    }
}
