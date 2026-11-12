package io.github.capstasher.gako

import io.github.capstasher.gako.doc.Tree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Layer-group moves on a bottom-to-top list where each group sits right above its contents. */
class TreeMoveTest {
    data class Item(val id: Long, val parent: Long? = null, val group: Boolean = false)

    private fun move(items: List<Item>, id: Long, delta: Int) =
        Tree.move(items, id, delta, { it.id }, { it.parent }, { it.group }) { t, p -> t.copy(parent = p) }

    /** "id" or "id^parent", bottom first. */
    private fun show(items: List<Item>?) = items?.joinToString(" ") { if (it.parent == null) "${it.id}" else "${it.id}^${it.parent}" }

    // bottom → top:  1 (bg), 2 in G10, 3 in G10, G10, 4
    private val doc = listOf(Item(1), Item(2, 10), Item(3, 10), Item(10, group = true), Item(4))

    @Test fun swapsPlainSiblings() {
        assertEquals("1 2^10 3^10 10 4", show(doc))
        assertEquals("1 3^10 2^10 10 4", show(move(doc, 2, +1)))
        assertEquals("1 3^10 2^10 10 4", show(move(doc, 3, -1)))
    }

    @Test fun raisingIntoAGroupAboveLandsAtItsBottom() {
        assertEquals("1^10 2^10 3^10 10 4", show(move(doc, 1, +1)))
    }

    @Test fun loweringIntoAGroupBelowLandsAtItsTop() {
        assertEquals("1 2^10 3^10 4^10 10", show(move(doc, 4, -1)))
    }

    @Test fun steppingOutOfAGroup() {
        // 3 is the top child: raising steps out above the group.
        assertEquals("1 2^10 10 3 4", show(move(doc, 3, +1)))
        // 2 is the bottom child: lowering steps out below the group.
        assertEquals("1 2 3^10 10 4", show(move(doc, 2, -1)))
    }

    @Test fun groupsMoveWithTheirContents() {
        assertEquals("1 4 2^10 3^10 10", show(move(doc, 10, +1)))
        assertEquals("2^10 3^10 10 1 4", show(move(doc, 10, -1)))
    }

    @Test fun edgesOfTheImage() {
        assertNull(move(doc, 4, +1))
        assertNull(move(doc, 1, -1))
    }

    @Test fun nestedGroupsStepOutOneLevelAtATime() {
        // bottom → top: 1, 2 in G20, G20 in G10, G10
        val nested = listOf(Item(1), Item(2, 20), Item(20, 10, group = true), Item(10, group = true))
        val out1 = move(nested, 2, +1)
        assertEquals("1 20^10 2^10 10", show(out1))
        val out2 = move(out1!!, 2, +1)
        assertEquals("1 20^10 10 2", show(out2))
    }
}
