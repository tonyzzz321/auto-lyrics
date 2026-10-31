package com.autolyrics.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.accessibility.AccessibilityEvent
import com.autolyrics.lyrics.KaraokeTiming
import com.autolyrics.model.LyricLine
import com.autolyrics.util.LyricWordLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Animated full-screen lyric stack used only by Performance mode.
 *
 * Rendering deliberately keeps provider timing tokens separate from display
 * text. [LyricWordLayout] reconstructs the provider's original separators and
 * maps fine-grained timing tokens onto readable display units, so SyncLRC and
 * Musixmatch timing can animate without inserting synthetic spaces.
 */
class PerformanceLyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    var positionProvider: (() -> Long)? = null

    var isPlaying: Boolean = false
        set(value) {
            val changedToPlaying = value && !field
            field = value
            if (changedToPlaying) resume()
        }

    private var activeColor = Color.WHITE
    private var inactiveColor = Color.parseColor("#66FFFFFF")
    private var highlightColor = Color.parseColor("#FFD54F")

    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(30f)
        isFakeBoldText = true
    }

    private val translationPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(20f)
    }

    private var lines: List<LyricLine> = emptyList()
    private var translations: List<String>? = null
    private var plainMode = false
    private var trackDurationMs = 0L
    private var message: String? = null
    private var targetLine = 0
    private var activeLine = -1
    private var lastLinesIdentity = 0
    private var lastAccessibilityText: String? = null

    private var animIndex = 0f
    private var animVelocity = 0f
    private var targetIndex = 0f
    private var scrollY = 0f

    private data class RenderedText(
        val text: String,
        val tokenStart: IntArray,
        val tokenEnd: IntArray
    )

    private data class LineLayout(
        val layout: StaticLayout,
        val translationLayout: StaticLayout?,
        val text: String,
        val tokenDisplayStart: IntArray,
        val tokenDisplayEnd: IntArray,
        val maxScale: Float
    )

    private var layouts: List<LineLayout> = emptyList()
    private var lineTop = FloatArray(0)
    private var lineCenter = FloatArray(0)
    private var needsRebuild = false
    private var builtForWidth = -1

    fun setColors(active: Int, inactive: Int, highlight: Int) {
        if (activeColor == active && inactiveColor == inactive && highlightColor == highlight) return
        activeColor = active
        inactiveColor = inactive
        highlightColor = highlight
        invalidate()
    }

    fun setLyrics(
        newLines: List<LyricLine>,
        plain: Boolean,
        durationMs: Long,
        linesId: Int,
        newTranslations: List<String>? = null
    ) {
        val identityChanged = linesId != lastLinesIdentity
        val contentChanged = newLines !== lines || plain != plainMode || message != null ||
            newTranslations !== translations

        translations = newTranslations
        lines = newLines
        plainMode = plain
        trackDurationMs = durationMs
        message = null

        if (identityChanged) {
            lastLinesIdentity = linesId
            targetLine = 0
            activeLine = -1
            targetIndex = 0f
            animIndex = 0f
            animVelocity = 0f
        }

        updateAccessibilityText(
            if (plainMode) newLines.joinToString("\n") { it.text }
            else newLines.getOrNull(activeLine)?.text
        )

        if (contentChanged) requestRebuild() else resume()
    }

    fun setActiveLine(index: Int) {
        if (index < 0) {
            activeLine = -1
            targetLine = 0
            updateAccessibilityText(null)
            if (abs(animIndex) > SNAP_JUMP_LINES) {
                animIndex = 0f
                animVelocity = 0f
            }
            resume()
            return
        }
        if (index !in lines.indices) return
        activeLine = index
        targetLine = index
        updateAccessibilityText(lines[index].text)
        if (abs(index - animIndex) > SNAP_JUMP_LINES) {
            animIndex = index.toFloat()
            animVelocity = 0f
        }
        resume()
    }

    fun setMessage(text: String?) {
        if (message == text && lines.isEmpty()) return
        message = text
        if (text != null) {
            lines = emptyList()
            layouts = emptyList()
        }
        updateAccessibilityText(text)
        invalidate()
    }

    private fun updateAccessibilityText(text: String?) {
        val normalized = text?.takeIf { it.isNotBlank() }
        if (lastAccessibilityText == normalized) return
        lastAccessibilityText = normalized
        contentDescription = normalized
        if (isAttachedToWindow && normalized != null) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        }
    }

    private fun requestRebuild() {
        needsRebuild = true
        resume()
        invalidate()
    }

    private fun rebuildLayouts() {
        needsRebuild = false
        builtForWidth = width
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val newLayouts = ArrayList<LineLayout>(lines.size)
        val newTops = FloatArray(lines.size)
        val newCenters = FloatArray(lines.size)
        var runningTop = 0f

        lines.forEachIndexed { index, line ->
            val rendered = renderedText(line)
            val layout = StaticLayout.Builder.obtain(
                rendered.text,
                0,
                rendered.text.length,
                textPaint,
                availableWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .build()

            val displayStarts = rendered.tokenStart
            val displayEnds = rendered.tokenEnd

            val widestLine = (0 until layout.lineCount)
                .maxOfOrNull { visualLine -> layout.getLineWidth(visualLine) }
                ?.coerceAtLeast(1f)
                ?: 1f
            val maxScale = (availableWidth.toFloat() / widestLine)
                .coerceIn(INACTIVE_SCALE, ACTIVE_SCALE)

            val translationText = translations?.getOrNull(index)?.takeIf { it.isNotBlank() }
            val translationLayout = translationText?.let { text ->
                StaticLayout.Builder.obtain(text, 0, text.length, translationPaint, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.12f)
                    .setIncludePad(false)
                    .build()
            }
            val blockHeight = layout.height +
                (translationLayout?.let { spToPx(TRANSLATION_GAP_SP) + it.height } ?: 0f)

            newTops[index] = runningTop
            newCenters[index] = runningTop + blockHeight / 2f
            newLayouts += LineLayout(
                layout,
                translationLayout,
                rendered.text,
                displayStarts,
                displayEnds,
                maxScale
            )
            runningTop += blockHeight + spToPx(INTER_LINE_GAP_SP)
        }

        layouts = newLayouts
        lineTop = newTops
        lineCenter = newCenters
    }

    private fun renderedText(line: LyricLine): RenderedText {
        if (line.words.isEmpty()) {
            return RenderedText(line.text.ifBlank { "♪" }, IntArray(0), IntArray(0))
        }

        val rendered = LyricWordLayout.renderedLine(line)
        return RenderedText(rendered.text, rendered.tokenStart, rendered.tokenEnd)
    }

    private var running = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) {
                0.016f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            lastFrameNanos = frameTimeNanos

            step(dt)
            invalidate()

            if (isBusy()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastFrameNanos = 0L
            }
        }
    }

    private fun step(dt: Float) {
        if ((needsRebuild || builtForWidth != width) && width > 0) rebuildLayouts()
        if (layouts.isEmpty()) return

        val maxIndex = layouts.lastIndex.toFloat()
        targetIndex = if (plainMode && trackDurationMs > 0L) {
            val progress = (safePosition().coerceAtLeast(0L).toFloat() / trackDurationMs)
                .coerceIn(0f, 1f)
            progress * maxIndex
        } else {
            targetLine.toFloat().coerceIn(0f, maxIndex)
        }

        if (plainMode && abs(targetIndex - animIndex) > SNAP_JUMP_LINES) {
            animIndex = targetIndex
            animVelocity = 0f
        }

        val stiffness = 120f
        val damping = 2f * DAMPING_RATIO * sqrt(stiffness)
        val substeps = ceil(dt / 0.008f).toInt().coerceIn(1, 8)
        val h = dt / substeps
        repeat(substeps) {
            val acceleration = stiffness * (targetIndex - animIndex) - damping * animVelocity
            animVelocity += acceleration * h
            animIndex += animVelocity * h
        }

        animIndex = animIndex.coerceIn(0f, maxIndex)
        scrollY = centerOf(animIndex) - height * FOCAL_Y_FRACTION
    }

    private fun isBusy(): Boolean {
        if (layouts.isEmpty()) return false
        val settled = abs(targetIndex - animIndex) < SETTLE_EPS &&
            abs(animVelocity) < SETTLE_EPS
        if (!settled) return true
        if (!isPlaying) return false
        if (plainMode) return trackDurationMs > 0L
        return hasActiveKaraokeAnimation()
    }

    private fun hasActiveKaraokeAnimation(): Boolean {
        val line = lines.getOrNull(activeLine) ?: return false
        val lineLayout = layouts.getOrNull(activeLine) ?: return false
        val position = safePosition()
        val activeToken = KaraokeTiming.activeWordIndex(line.words, position)
        if (activeToken !in line.words.indices) return false

        var groupLast = activeToken
        while (groupLast + 1 < line.words.size &&
            sameDisplayRange(lineLayout, groupLast + 1, activeToken)
        ) {
            groupLast++
        }

        val groupStartMs = line.words[activeToken].timeMs
        val groupEndMs = line.words[groupLast].endTimeMs
            ?: line.words.getOrNull(groupLast + 1)?.timeMs
            ?: (groupStartMs + LAST_GROUP_MS)
        return position < groupEndMs
    }

    private fun centerOf(index: Float): Float {
        val clamped = index.coerceIn(0f, layouts.lastIndex.toFloat())
        val lower = floor(clamped).toInt()
        val upper = ceil(clamped).toInt().coerceAtMost(layouts.lastIndex)
        if (lower == upper) return lineCenter[lower]
        val fraction = clamped - lower
        return lineCenter[lower] + (lineCenter[upper] - lineCenter[lower]) * fraction
    }

    fun resume() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) resume() else pause()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) requestRebuild()
    }

    override fun onDraw(canvas: Canvas) {
        message?.let {
            drawMessage(canvas, it)
            return
        }

        if (layouts.isEmpty()) {
            if (needsRebuild && width > 0) rebuildLayouts()
            if (layouts.isEmpty()) return
        }

        val centerX = width / 2f
        layouts.indices.forEach { index ->
            val lineLayout = layouts[index]
            val drawnTop = lineTop[index] - scrollY
            if (drawnTop + lineLayout.layout.height < -DRAW_MARGIN_PX ||
                drawnTop > height + DRAW_MARGIN_PX
            ) {
                return@forEach
            }

            val focus = if (!plainMode && activeLine < 0) {
                0f
            } else {
                smoothstep((1f - abs(index - animIndex)).coerceIn(0f, 1f))
            }
            val desiredScale = INACTIVE_SCALE + (ACTIVE_SCALE - INACTIVE_SCALE) * focus
            val scale = minOf(desiredScale, lineLayout.maxScale)
            val activeKaraoke = !plainMode && index == activeLine &&
                lineLayout.tokenDisplayStart.isNotEmpty()
            val alpha = if (activeKaraoke) 1f else INACTIVE_ALPHA + (1f - INACTIVE_ALPHA) * focus
            val color = if (activeKaraoke) activeColor else lerpColor(inactiveColor, activeColor, focus)

            canvas.save()
            canvas.translate(paddingLeft.toFloat(), drawnTop)
            canvas.scale(scale, scale, centerX - paddingLeft, lineLayout.layout.height / 2f)

            textPaint.color = color
            textPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            lineLayout.layout.draw(canvas)

            lineLayout.translationLayout?.let { translationLayout ->
                canvas.save()
                canvas.translate(0f, lineLayout.layout.height + spToPx(TRANSLATION_GAP_SP))
                translationPaint.color = color
                translationPaint.alpha = (alpha * 255 * 0.85f).toInt().coerceIn(0, 255)
                translationLayout.draw(canvas)
                canvas.restore()
            }

            if (activeKaraoke) {
                drawWordHighlight(canvas, lineLayout, lines[index], alpha)
            }
            canvas.restore()
        }
    }

    private fun drawWordHighlight(
        canvas: Canvas,
        lineLayout: LineLayout,
        line: LyricLine,
        alpha: Float
    ) {
        val position = safePosition()
        val activeToken = KaraokeTiming.activeWordIndex(line.words, position)
        if (activeToken !in line.words.indices) return

        val start = lineLayout.tokenDisplayStart[activeToken]
        val end = lineLayout.tokenDisplayEnd[activeToken]
        if (start >= end) return

        var groupFirst = activeToken
        while (groupFirst > 0 && sameDisplayRange(lineLayout, groupFirst - 1, activeToken)) groupFirst--
        var groupLast = activeToken
        while (groupLast + 1 < line.words.size &&
            sameDisplayRange(lineLayout, groupLast + 1, activeToken)
        ) {
            groupLast++
        }

        val groupStartMs = line.words[groupFirst].timeMs
        val groupEndMs = line.words[groupLast].endTimeMs
            ?: line.words.getOrNull(groupLast + 1)?.timeMs
            ?: (groupStartMs + LAST_GROUP_MS)
        val progress = ((position - groupStartMs).toFloat() /
            (groupEndMs - groupStartMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
        val emphasis = sin(PI.toFloat() * smoothstep(progress))
        val emphasisColor = lerpColor(activeColor, highlightColor, 0.65f * emphasis)

        val layout = lineLayout.layout
        val boundedEnd = end.coerceAtMost(lineLayout.text.length)
        val firstVisualLine = layout.getLineForOffset(start)
        val lastVisualLine = layout.getLineForOffset((boundedEnd - 1).coerceAtLeast(start))

        if (firstVisualLine != lastVisualLine) {
            drawHighlightedRange(canvas, layout, start, boundedEnd, alpha, emphasisColor)
            return
        }

        val xStart = layout.getPrimaryHorizontal(start)
        val xEnd = layout.getPrimaryHorizontal(boundedEnd)
        val left = minOf(xStart, xEnd)
        val right = maxOf(xStart, xEnd)
        val top = layout.getLineTop(firstVisualLine).toFloat()
        val bottom = layout.getLineBottom(firstVisualLine).toFloat()

        canvas.save()
        canvas.clipRect(left - 2f, top, right + 2f, bottom)
        textPaint.color = emphasisColor
        textPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawHighlightedRange(
        canvas: Canvas,
        layout: StaticLayout,
        start: Int,
        end: Int,
        alpha: Float,
        color: Int
    ) {
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset((end - 1).coerceAtLeast(start))
        for (visualLine in firstLine..lastLine) {
            val segmentStart = maxOf(start, layout.getLineStart(visualLine))
            val segmentEnd = minOf(end, layout.getLineEnd(visualLine))
            if (segmentStart >= segmentEnd) continue
            val x1 = layout.getPrimaryHorizontal(segmentStart)
            val x2 = layout.getPrimaryHorizontal(segmentEnd)
            canvas.save()
            canvas.clipRect(
                minOf(x1, x2) - 2f,
                layout.getLineTop(visualLine).toFloat(),
                maxOf(x1, x2) + 2f,
                layout.getLineBottom(visualLine).toFloat()
            )
            textPaint.color = color
            textPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun sameDisplayRange(layout: LineLayout, left: Int, right: Int): Boolean =
        layout.tokenDisplayStart[left] == layout.tokenDisplayStart[right] &&
            layout.tokenDisplayEnd[left] == layout.tokenDisplayEnd[right]

    private fun drawMessage(canvas: Canvas, text: String) {
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        textPaint.color = inactiveColor
        textPaint.alpha = 255
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun safePosition(): Long = try {
        positionProvider?.invoke() ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    companion object {
        private const val FOCAL_Y_FRACTION = 0.46f
        private const val ACTIVE_SCALE = 1.22f
        private const val INACTIVE_SCALE = 0.78f
        private const val INACTIVE_ALPHA = 0.30f
        private const val INTER_LINE_GAP_SP = 18f
        private const val TRANSLATION_GAP_SP = 3f
        private const val DAMPING_RATIO = 0.82f
        private const val SETTLE_EPS = 0.0015f
        private const val SNAP_JUMP_LINES = 6f
        private const val LAST_GROUP_MS = 650L
        private const val DRAW_MARGIN_PX = 160f

        private fun smoothstep(value: Float): Float {
            val t = value.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
            val f = fraction.coerceIn(0f, 1f)
            val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
            val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
            val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
            val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
            return Color.argb(a, r, g, b)
        }
    }
}