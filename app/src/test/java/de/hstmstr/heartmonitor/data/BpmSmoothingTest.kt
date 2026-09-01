package de.hstmstr.heartmonitor.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BpmSmoothingTest {

    private fun track(vararg bpm: Int) =
        bpm.mapIndexed { i, v -> BpmTrackPoint(elapsedSeconds = i.toDouble(), bpm = v) }

    @Test
    fun `window below 3 returns the raw values unchanged`() {
        val t = track(60, 80, 100)
        assertThat(t.movingAverageBpm(0)).containsExactly(60.0, 80.0, 100.0).inOrder()
        assertThat(t.movingAverageBpm(1)).containsExactly(60.0, 80.0, 100.0).inOrder()
        assertThat(t.movingAverageBpm(2)).containsExactly(60.0, 80.0, 100.0).inOrder()
    }

    @Test
    fun `output has the same size and order as the input`() {
        val t = track(70, 71, 72, 73, 74, 75, 76)
        assertThat(t.movingAverageBpm(5)).hasSize(t.size)
    }

    @Test
    fun `empty track yields an empty result`() {
        assertThat(emptyList<BpmTrackPoint>().movingAverageBpm(5)).isEmpty()
    }

    @Test
    fun `constant signal is unchanged`() {
        val t = track(90, 90, 90, 90, 90, 90)
        assertThat(t.movingAverageBpm(5)).containsExactly(90.0, 90.0, 90.0, 90.0, 90.0, 90.0)
    }

    @Test
    fun `window shrinks symmetrically at the edges so ends stay on the raw sample`() {
        // window 3 -> radius 1. Index 0 and last use radius 0 (raw); the middle
        // ones average their two neighbours.
        val t = track(10, 30, 10, 30, 10)
        val s = t.movingAverageBpm(3)
        assertThat(s[0]).isEqualTo(10.0)
        assertThat(s[1]).isWithin(1e-9).of((10 + 30 + 10) / 3.0)
        assertThat(s[2]).isWithin(1e-9).of((30 + 10 + 30) / 3.0)
        assertThat(s[3]).isWithin(1e-9).of((10 + 30 + 10) / 3.0)
        assertThat(s[4]).isEqualTo(10.0)
    }

    @Test
    fun `an even window behaves like the odd value one below it`() {
        val t = track(10, 30, 10, 30, 10)
        assertThat(t.movingAverageBpm(4)).isEqualTo(t.movingAverageBpm(3))
    }

    @Test
    fun `averaging never crosses a recording gap`() {
        val t = listOf(
            BpmTrackPoint(0.0, 100),
            BpmTrackPoint(1.0, 100),
            BpmTrackPoint(2.0, 100),
            BpmTrackPoint(120.0, 160, gapBefore = true),
            BpmTrackPoint(121.0, 160),
            BpmTrackPoint(122.0, 160),
        )
        val s = t.movingAverageBpm(5)
        // Each segment is a constant block; smoothing stays inside it.
        assertThat(s).containsExactly(100.0, 100.0, 100.0, 160.0, 160.0, 160.0).inOrder()
    }

    @Test
    fun `interior point with a full window is the mean of that window`() {
        val t = track(50, 60, 70, 80, 90, 100, 110)
        // Index 3, window 5 -> mean of 60,70,80,90,100.
        assertThat(t.movingAverageBpm(5)[3]).isWithin(1e-9).of(80.0)
    }

    @Test
    fun `smoothing pulls down an isolated spike`() {
        val t = track(70, 70, 70, 140, 70, 70, 70)
        val spike = t.movingAverageBpm(5)[3]
        assertThat(spike).isLessThan(140.0)
        assertThat(spike).isWithin(1e-9).of((70 + 70 + 140 + 70 + 70) / 5.0)
    }
}
