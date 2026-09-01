package de.hstmstr.heartmonitor.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceScanListTest {

    private fun dev(
        address: String,
        name: String? = "Dev $address",
        rssi: Int = -60,
        hr: Boolean = false,
    ) = DiscoveredDevice(address, name, rssi, hr)

    @Test
    fun `first sighting is added`() {
        val list = DeviceScanList.merge(emptyList(), dev("A", rssi = -50))
        assertThat(list).hasSize(1)
        assertThat(list.single().address).isEqualTo("A")
    }

    @Test
    fun `same address is not duplicated`() {
        var list = DeviceScanList.merge(emptyList(), dev("A", rssi = -50))
        list = DeviceScanList.merge(list, dev("A", rssi = -70))
        assertThat(list).hasSize(1)
    }

    @Test
    fun `rssi follows the newest sighting`() {
        var list = DeviceScanList.merge(emptyList(), dev("A", rssi = -50))
        list = DeviceScanList.merge(list, dev("A", rssi = -80))
        assertThat(list.single().rssi).isEqualTo(-80)
    }

    @Test
    fun `advertisesHrService is sticky once seen`() {
        var list = DeviceScanList.merge(emptyList(), dev("A", hr = true))
        list = DeviceScanList.merge(list, dev("A", hr = false))
        assertThat(list.single().advertisesHrService).isTrue()
    }

    @Test
    fun `advertisesHrService can turn on from a later sighting`() {
        var list = DeviceScanList.merge(emptyList(), dev("A", hr = false))
        list = DeviceScanList.merge(list, dev("A", hr = true))
        assertThat(list.single().advertisesHrService).isTrue()
    }

    @Test
    fun `a non-blank name is not overwritten by a blank one`() {
        var list = DeviceScanList.merge(emptyList(), dev("A", name = "HR50"))
        list = DeviceScanList.merge(list, dev("A", name = null))
        assertThat(list.single().name).isEqualTo("HR50")
        list = DeviceScanList.merge(list, dev("A", name = "  "))
        assertThat(list.single().name).isEqualTo("HR50")
    }

    @Test
    fun `a blank name is filled in once a real one arrives`() {
        var list = DeviceScanList.merge(emptyList(), dev("A", name = null))
        list = DeviceScanList.merge(list, dev("A", name = "HR50"))
        assertThat(list.single().name).isEqualTo("HR50")
    }

    @Test
    fun `hr-service devices sort before plain ones`() {
        var list = DeviceScanList.merge(emptyList(), dev("plain", rssi = -40, hr = false))
        list = DeviceScanList.merge(list, dev("hr", rssi = -90, hr = true))
        assertThat(list.map { it.address }).containsExactly("hr", "plain").inOrder()
    }

    @Test
    fun `within the same class the stronger signal sorts first`() {
        var list = DeviceScanList.merge(emptyList(), dev("far", rssi = -95))
        list = DeviceScanList.merge(list, dev("near", rssi = -42))
        list = DeviceScanList.merge(list, dev("mid", rssi = -70))
        assertThat(list.map { it.address }).containsExactly("near", "mid", "far").inOrder()
    }

    @Test
    fun `ordering stays stable after an rssi update reshuffles it`() {
        var list = DeviceScanList.merge(emptyList(), dev("A", rssi = -40))
        list = DeviceScanList.merge(list, dev("B", rssi = -50))
        // A weakens below B
        list = DeviceScanList.merge(list, dev("A", rssi = -80))
        assertThat(list.map { it.address }).containsExactly("B", "A").inOrder()
    }

    @Test
    fun `equal rssi and class fall back to address order`() {
        var list = DeviceScanList.merge(emptyList(), dev("BB:BB", rssi = -60))
        list = DeviceScanList.merge(list, dev("AA:AA", rssi = -60))
        assertThat(list.map { it.address }).containsExactly("AA:AA", "BB:BB").inOrder()
    }

    @Test
    fun `displayName falls back for a blank name`() {
        assertThat(DiscoveredDevice("A", null, -60, false).displayName).isEqualTo("(unbenanntes Gerät)")
        assertThat(DiscoveredDevice("A", "HR50", -60, false).displayName).isEqualTo("HR50")
    }
}
