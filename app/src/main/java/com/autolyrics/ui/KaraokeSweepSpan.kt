package com.autolyrics.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import com.autolyrics.model.LyricLine
import com.autolyrics.util.LyricWordLayout
import kotlin.math.roundToInt

internal object PhoneKaraokeSweep {

    data class Segment(
        val start: Int,
        val end: Int,
        val startTimeMs: Long,
        val endTimeMs: Long
    )

    fun segmentAtPosition(line: LyricLine, positionMs: Long): Segment? {
        if (line.words.isEmpty()) return null

        var latestIndex = -1
        var latestTime = Long.MIN_VALUE
        // Tolerate out-of-order tokens like KaraokeTiming does; a break at the
        // first future token freezes the sweep while the song keeps singing.
        for (index in line.words.indices) {
            val time = line.words[index].timeMs
            if (time <= positionMs && time >= latestTime) {
                latestIndex = index
                latestTime = time
            }
        }
        if (latestIndex < 0) return null

        val ranges = LyricWordLayout.textSpaceRanges(line) ?: return null
        val activeRange = ranges.getOrNull(latestIndex) ?: return null

        var groupFirst = latestIndex
        while (groupFirst > 0 && sameDisplayRange(line, ranges, groupFirst - 1, activeRange.start, activeRange.end)) {
            groupFirst--
        }

        var groupLast = latestIndex
        while (
            groupLast + 1 < line.words.size &&
            sameDisplayRange(line, ranges, groupLast + 1, activeRange.start, activeRange.end)
        ) {
            groupLast++
        }

        val groupStartMs = line.words[groupFirst].timeMs
        val groupEndMs = line.words[groupLast].endTimeMs
            ?: line.words.getOrNull(groupLast + 1)?.timeMs
            ?: (groupStartMs + LAST_GROUP_MS)

        return Segment(
            start = activeRange.start,
            end = activeRange.end,
            startTimeMs = groupStartMs,
            endTimeMs = groupEndMs.coerceAtLeast(groupStartMs + 1L)
        )
    }

    fun isAnimating(line: LyricLine, positionMs: Long): Boolean {
        val segment = segmentAtPosition(line, positionMs) ?: return false
        return positionMs >= segment.startTimeMs && positionMs < segment.endTimeMs
    }

    private fun sameDisplayRange(
        line: LyricLine,
        ranges: List<LyricWordLayout.DisplayRange>,
        tokenIndex: Int,
        start: Int,
        end: Int
    ): Boolean {
        val range = ranges.getOrNull(tokenIndex) ?: return false
        return range.start == start && range.end == end
    }

    private const val LAST_GROUP_MS = 650L
}

internal class KaraokeSweepSpan(
    private val segment: PhoneKaraokeSweep.Segment,
    private val pendingColor: Int,
    private val completedColor: Int,
    private val positionProvider: () -> Long
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = paint.measureText(text, start, end).roundToInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val originalColor = paint.color
        val rendered = text.subSequence(start, end).toString()
        val width = paint.measureText(rendered)

        paint.color = pendingColor
        canvas.drawText(rendered, x, y.toFloat(), paint)

        val progress = ((positionProvider() - segment.startTimeMs).toFloat() /
            (segment.endTimeMs - segment.startTimeMs).coerceAtLeast(1L))
            .coerceIn(0f, 1f)

        if (progress > 0f && width > 0f) {
            canvas.save()
            canvas.clipRect(x, top.toFloat(), x + width * progress, bottom.toFloat())
            paint.color = completedColor
            canvas.drawText(rendered, x, y.toFloat(), paint)
            canvas.restore()
        }

        paint.color = originalColor
    }
}
