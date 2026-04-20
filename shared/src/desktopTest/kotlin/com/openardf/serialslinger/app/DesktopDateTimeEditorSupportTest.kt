package com.openardf.serialslinger.app

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDateTimeEditorSupportTest {
    @Test
    fun selectionRange_forHourAndMinuteMatchesDisplayLayout() {
        val text = "2026-04-20 13:45"

        assertEquals(11 to 13, DesktopDateTimeEditorSupport.selectionRangeForField(text, Calendar.HOUR_OF_DAY))
        assertEquals(14 to 16, DesktopDateTimeEditorSupport.selectionRangeForField(text, Calendar.MINUTE))
    }

    @Test
    fun explicitFieldFromSelection_detectsFullSegmentOnly() {
        val text = "2026-04-20 13:45"

        assertEquals(
            Calendar.HOUR_OF_DAY,
            DesktopDateTimeEditorSupport.explicitFieldFromSelection(text, 11, 13),
        )
        assertNull(DesktopDateTimeEditorSupport.explicitFieldFromSelection(text, 0, text.length))
    }

    @Test
    fun fieldFromSelection_usesFallbackForAmbiguousRange() {
        val text = "2026-04-20 13:45"

        assertEquals(
            Calendar.HOUR_OF_DAY,
            DesktopDateTimeEditorSupport.fieldFromSelection(
                text = text,
                selectionStart = 0,
                selectionEnd = text.length,
                caretPosition = 0,
                fallbackField = Calendar.HOUR_OF_DAY,
            ),
        )
    }

    @Test
    fun adjacentField_walksVisibleSegmentsOnly() {
        val withoutSeconds = "2026-04-20 13:45"
        val withSeconds = "2026-04-20 13:45:22"

        assertEquals(
            Calendar.MINUTE,
            DesktopDateTimeEditorSupport.adjacentField(withoutSeconds, Calendar.HOUR_OF_DAY, forward = true),
        )
        assertEquals(
            Calendar.SECOND,
            DesktopDateTimeEditorSupport.adjacentField(withSeconds, Calendar.MINUTE, forward = true),
        )
        assertEquals(
            Calendar.YEAR,
            DesktopDateTimeEditorSupport.adjacentField(withoutSeconds, Calendar.YEAR, forward = false),
        )
    }
}
