package com.autolyrics.lyrics

import com.autolyrics.model.LyricWord

/** Shared active-word selection for phone, Performance, and Android Auto karaoke. */
object KaraokeTiming {

    fun activeWordIndex(words: List<LyricWord>, positionMs: Long): Int {
        var latestIndex = -1
        var latestTime = Long.MIN_VALUE
        // Providers can emit tokens out of chronological order (RichSync chunk
        // offsets are not reliably monotonic for non-Latin scripts). Scan every
        // token instead of breaking at the first future token, otherwise an
        // early out-of-order entry freezes the highlight until the line ends.
        for (index in words.indices) {
            val time = words[index].timeMs
            if (time <= positionMs && time >= latestTime) {
                latestIndex = index
                latestTime = time
            }
        }

        if (latestIndex < 0) return -1

        val latestWord = words[latestIndex]
        val explicitEnd = latestWord.endTimeMs
        return if (explicitEnd != null && positionMs >= explicitEnd) {
            -1
        } else {
            latestIndex
        }
    }
}
