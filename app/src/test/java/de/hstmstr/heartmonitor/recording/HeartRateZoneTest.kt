package de.hstmstr.heartmonitor.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeartRateZoneTest {

    @Test
    fun `of maps bpm just below a boundary to the lower zone`() {
        assertThat(HeartRateZone.of(113)).isEqualTo(HeartRateZone.Z1)
        assertThat(HeartRateZone.of(132)).isEqualTo(HeartRateZone.Z2)
        assertThat(HeartRateZone.of(151)).isEqualTo(HeartRateZone.Z3)
        assertThat(HeartRateZone.of(170)).isEqualTo(HeartRateZone.Z4)
    }

    @Test
    fun `of maps a boundary bpm to the higher zone`() {
        assertThat(HeartRateZone.of(114)).isEqualTo(HeartRateZone.Z2)
        assertThat(HeartRateZone.of(133)).isEqualTo(HeartRateZone.Z3)
        assertThat(HeartRateZone.of(152)).isEqualTo(HeartRateZone.Z4)
        assertThat(HeartRateZone.of(171)).isEqualTo(HeartRateZone.Z5)
    }

    @Test
    fun `of clamps out-of-range bpm into the end zones`() {
        assertThat(HeartRateZone.of(0)).isEqualTo(HeartRateZone.Z1)
        assertThat(HeartRateZone.of(-20)).isEqualTo(HeartRateZone.Z1)
        assertThat(HeartRateZone.of(240)).isEqualTo(HeartRateZone.Z5)
    }

    @Test
    fun `upperBpm is the next zone's lower bound and null at the top`() {
        assertThat(HeartRateZone.Z1.upperBpm).isEqualTo(114)
        assertThat(HeartRateZone.Z4.upperBpm).isEqualTo(171)
        assertThat(HeartRateZone.Z5.upperBpm).isNull()
    }

    @Test
    fun `zones are declared in ascending bpm order`() {
        val bounds = HeartRateZone.entries.map { it.lowerBpm }
        assertThat(bounds).isEqualTo(bounds.sorted())
        assertThat(bounds.toSet()).hasSize(bounds.size)
    }
}
