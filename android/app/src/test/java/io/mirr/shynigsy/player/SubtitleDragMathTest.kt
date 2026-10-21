package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class SubtitleDragMathTest {
    @Test fun horizontalCaptionCannotBeDraggedOutsideThePicture() {
        val bounds = SubtitleDragBounds(300f, 700f, 700f, 780f)
        val moved = SubtitleDragMath.move(0, 0, 5000f, 5000f, 1000, 800, bounds, 8f)
        assertTrue(bounds.right + moved.translationX <= 992f)
        assertTrue(bounds.bottom + moved.translationY <= 792f)
        assertTrue(bounds.right + moved.x * 10f <= 992f)
        assertTrue(bounds.bottom + moved.y * 8f <= 792f)
    }
    @Test fun verticalCaptionMovesLeftAndUpWithIndependentCoordinates() {
        val moved = SubtitleDragMath.move(0, 0, -200f, -80f, 1000, 800,
            SubtitleDragBounds(850f, 200f, 930f, 600f), 8f)
        assertEquals(-20, moved.x); assertEquals(-10, moved.y)
        assertEquals(-200f, moved.translationX, 0f)
        assertEquals(-80f, moved.translationY, 0f)
    }
    @Test fun storedPositionRemainsSafeAtTheTopLeftBoundary() {
        val moved = SubtitleDragMath.move(15, -10, -5000f, -5000f, 1200, 600,
            SubtitleDragBounds(250f, 120f, 650f, 200f), 12f)
        assertTrue(250f + (moved.x - 15) * 12f >= 12f)
        assertTrue(120f + (moved.y + 10) * 6f >= 12f)
    }
    @Test fun invalidViewportOrNonFiniteMovementKeepsTheOriginalPosition() {
        val moved = SubtitleDragMath.move(5, -3, Float.NaN, 50f, 0, 0,
            SubtitleDragBounds(0f, 0f, 100f, 100f), 8f)
        assertEquals(SubtitleDragOffset(5, -3, 0f, 0f), moved)
    }
    @Test fun extremeVerticalCaptionDragDoesNotPreviewBeyondTheStoredRange() {
        val moved = SubtitleDragMath.move(0, 0, -5000f, 0f, 1280, 720,
            SubtitleDragBounds(1150f, 150f, 1185f, 570f), 8f)
        assertEquals(-80, moved.x)
        assertEquals(-1024f, moved.translationX, 0f)
    }
}
