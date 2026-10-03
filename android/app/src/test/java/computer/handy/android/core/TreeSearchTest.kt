package computer.handy.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreeSearchTest {

    private data class Node(val name: String, val focused: Boolean = false, val children: List<Node> = emptyList())

    // A Compose/WebView-like host whose focused field is a few levels down.
    private val tree = Node(
        "host",
        children = listOf(
            Node("toolbar", children = listOf(Node("title"))),
            Node("content", children = listOf(Node("list"), Node("composer", children = listOf(Node("field", focused = true))))),
        ),
    )

    @Test
    fun `finds the focused node in a nested tree`() {
        assertEquals("field", TreeSearch.findFirst(tree, { it.children }, limit = 100) { it.focused }?.name)
    }

    @Test
    fun `stops after the visit limit`() {
        assertNull(TreeSearch.findFirst(tree, { it.children }, limit = 3) { it.focused })
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(TreeSearch.findFirst(tree, { it.children }, limit = 100) { it.name == "missing" })
    }
}
