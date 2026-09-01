package de.hstmstr.heartmonitor.ble

/**
 * Pure list-maintenance logic for the device picker: fold each incoming scan
 * sighting into the running list of [DiscoveredDevice]s.
 *
 * Kept free of Android types so it can be unit-tested. [HeartRateBleManager]
 * feeds it raw sightings from `ScanCallback`; the UI renders the result.
 *
 * Rules:
 *  - one entry per [DiscoveredDevice.address] (later sightings update it)
 *  - [DiscoveredDevice.rssi] and [DiscoveredDevice.name] follow the newest
 *    sighting, except a non-blank name is never overwritten with a blank one
 *  - [DiscoveredDevice.advertisesHrService] is sticky: once any sighting of an
 *    address advertised 0x180D, the entry stays flagged
 *  - sort: 0x180D advertisers first, then by descending RSSI (closest first),
 *    then by address for a stable order
 */
object DeviceScanList {

    fun merge(current: List<DiscoveredDevice>, sighting: DiscoveredDevice): List<DiscoveredDevice> {
        val existing = current.firstOrNull { it.address == sighting.address }
        val updated = if (existing == null) {
            sighting
        } else {
            existing.copy(
                name = sighting.name?.takeIf { it.isNotBlank() } ?: existing.name,
                rssi = sighting.rssi,
                advertisesHrService = existing.advertisesHrService || sighting.advertisesHrService,
            )
        }
        return (current.filterNot { it.address == sighting.address } + updated).sortedWith(ORDER)
    }

    private val ORDER: Comparator<DiscoveredDevice> = compareByDescending<DiscoveredDevice> { it.advertisesHrService }
        .thenByDescending { it.rssi }
        .thenBy { it.address }
}
