package de.hstmstr.heartmonitor.data

/**
 * Centered moving-average of a bpm track, returned value-by-value aligned with
 * the input (same size, same order).
 *
 * The average never crosses a [BpmTrackPoint.gapBefore] boundary, so a recording
 * gap still splits the curve. Within `window / 2` samples of a segment edge the
 * window shrinks symmetrically to whatever still fits, so the smoothed curve
 * keeps touching the real first and last sample of every segment.
 *
 * @param window total samples in the average. A [window] below 3 (or a track
 *               shorter than two points) returns the raw bpm as [Double]
 *               unchanged; an even [window] behaves like the odd value one
 *               below it.
 */
fun List<BpmTrackPoint>.movingAverageBpm(window: Int): List<Double> {
    val radius = (window - 1) / 2
    if (radius <= 0 || size < 2) return map { it.bpm.toDouble() }

    val out = MutableList(size) { 0.0 }
    var segStart = 0
    for (i in 1..size) {
        if (i == size || this[i].gapBefore) {
            for (k in segStart until i) {
                // Largest symmetric radius that stays inside [segStart, i).
                val r = minOf(radius, k - segStart, i - 1 - k)
                var sum = 0
                for (j in k - r..k + r) sum += this[j].bpm
                out[k] = sum.toDouble() / (2 * r + 1)
            }
            segStart = i
        }
    }
    return out
}
