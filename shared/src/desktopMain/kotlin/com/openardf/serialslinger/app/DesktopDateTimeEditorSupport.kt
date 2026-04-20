package com.openardf.serialslinger.app

import java.util.Calendar

internal object DesktopDateTimeEditorSupport {
    fun selectionRangeForField(text: String, calendarField: Int): Pair<Int, Int> {
        return when (calendarField) {
            Calendar.YEAR -> 0 to 4
            Calendar.MONTH -> 5 to 7
            Calendar.DAY_OF_MONTH -> 8 to 10
            Calendar.HOUR_OF_DAY -> 11 to 13
            Calendar.MINUTE -> text.indexOf(':').let { colonIndex ->
                if (colonIndex >= 0) {
                    colonIndex + 1 to (colonIndex + 3).coerceAtMost(text.length)
                } else {
                    text.length to text.length
                }
            }
            Calendar.SECOND -> {
                val firstColon = text.indexOf(':')
                val secondColon = text.lastIndexOf(':')
                if (firstColon >= 0 && secondColon > firstColon) {
                    secondColon + 1 to (secondColon + 3).coerceAtMost(text.length)
                } else {
                    text.length to text.length
                }
            }
            else -> text.indexOf(':').let { colonIndex ->
                if (colonIndex >= 0) {
                    colonIndex + 1 to (colonIndex + 3).coerceAtMost(text.length)
                } else {
                    text.length to text.length
                }
            }
        }
    }

    fun fieldFromSelection(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        caretPosition: Int,
        fallbackField: Int = Calendar.MINUTE,
    ): Int {
        if (selectionStart != selectionEnd) {
            return explicitFieldFromSelection(text, selectionStart, selectionEnd) ?: fallbackField
        }
        return fieldFromOffset(caretPosition, fallbackField)
    }

    fun explicitFieldFromSelection(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): Int? {
        if (selectionStart == selectionEnd) {
            return null
        }
        val selectedRange = selectionStart to selectionEnd
        val candidateFields = listOf(
            Calendar.YEAR,
            Calendar.MONTH,
            Calendar.DAY_OF_MONTH,
            Calendar.HOUR_OF_DAY,
            Calendar.MINUTE,
            Calendar.SECOND,
        )
        return candidateFields.firstOrNull { calendarField ->
            selectionRangeForField(text, calendarField) == selectedRange
        }
    }

    fun fieldFromOffset(offset: Int, fallbackField: Int? = Calendar.MINUTE): Int {
        return when (offset) {
            in 0..3 -> Calendar.YEAR
            in 5..6 -> Calendar.MONTH
            in 8..9 -> Calendar.DAY_OF_MONTH
            in 11..12 -> Calendar.HOUR_OF_DAY
            in 14..15 -> Calendar.MINUTE
            in 17..18 -> Calendar.SECOND
            else -> fallbackField ?: Calendar.MINUTE
        }
    }

    fun adjacentField(text: String, currentField: Int, forward: Boolean): Int? {
        val fields = buildList {
            add(Calendar.YEAR)
            add(Calendar.MONTH)
            add(Calendar.DAY_OF_MONTH)
            add(Calendar.HOUR_OF_DAY)
            add(Calendar.MINUTE)
            if (text.count { it == ':' } >= 2) {
                add(Calendar.SECOND)
            }
        }
        val currentIndex = fields.indexOf(currentField).takeIf { it >= 0 } ?: return fields.firstOrNull()
        val targetIndex = if (forward) {
            (currentIndex + 1).coerceAtMost(fields.lastIndex)
        } else {
            (currentIndex - 1).coerceAtLeast(0)
        }
        return fields[targetIndex]
    }
}
