package computer.handy.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonPlacerTest {

    private val screen = Box(0, 0, 1080, 2400)
    private val keyboard = Box(0, 1500, 1080, 2400)
    private val size = 110
    private val gap = 16

    @Test
    fun `narrow field gets the button outside its bottom right corner`() {
        val field = Box(100, 400, 700, 520)
        val b = ButtonPlacer.place(field, screen, keyboard, size, gap)
        assertEquals(Box(716, 410, 826, 520), b)
    }

    @Test
    fun `full width field docked on the keyboard gets the button above it`() {
        val field = Box(0, 1360, 1080, 1490)
        val b = ButtonPlacer.place(field, screen, keyboard, size, gap)
        assertFalse(b.intersects(keyboard))
        assertFalse(b.intersects(field))
        assertEquals(field.top - gap, b.bottom)
        assertEquals(field.right, b.right)
    }

    @Test
    fun `never overlaps the keyboard`() {
        val field = Box(0, 1300, 1080, 1480) // the spot below would be on the keyboard
        val b = ButtonPlacer.place(field, screen, keyboard, size, gap)
        assertFalse(b.intersects(keyboard))
    }

    @Test
    fun `user offset moves the button vertically`() {
        val field = Box(100, 400, 700, 520)
        val b = ButtonPlacer.place(field, screen, keyboard, size, gap, userOffsetY = -200)
        assertEquals(210, b.top)
    }

    @Test
    fun `offset into the keyboard falls back to another valid spot`() {
        val field = Box(100, 1200, 700, 1320)
        val b = ButtonPlacer.place(field, screen, keyboard, size, gap, userOffsetY = 400)
        assertFalse(b.intersects(keyboard))
        assertTrue(screen.contains(b))
    }

    @Test
    fun `full screen editor clamps above the keyboard`() {
        val field = Box(0, 0, 1080, 2400)
        val b = ButtonPlacer.place(field, screen, keyboard, size, gap)
        assertFalse(b.intersects(keyboard))
        assertTrue(screen.contains(b))
    }

    @Test
    fun `without keyboard the bottom of the screen is usable`() {
        val field = Box(0, 2200, 1080, 2300)
        val b = ButtonPlacer.place(field, screen, null, size, gap)
        assertTrue(screen.contains(b))
        assertFalse(b.intersects(field))
    }
}
