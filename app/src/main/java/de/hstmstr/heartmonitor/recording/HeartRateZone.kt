package de.hstmstr.heartmonitor.recording

/**
 * Five fixed heart-rate training zones by absolute bpm. Pure – no Android – so
 * it is directly unit-tested.
 *
 * The app keeps no user profile, so the boundaries are static thresholds rather
 * than a percentage of an individual max heart rate: the classic 60 / 70 / 80 /
 * 90 % cut points of a 190 bpm reference, rounded to whole bpm
 * (114 / 133 / 152 / 171).
 *
 * @param label     short display name, e.g. `Zone 3`
 * @param lowerBpm  inclusive lower bound; a bpm belongs to the highest zone
 *                  whose [lowerBpm] it still reaches
 */
enum class HeartRateZone(val label: String, val lowerBpm: Int) {
    Z1("Zone 1", 0),
    Z2("Zone 2", 114),
    Z3("Zone 3", 133),
    Z4("Zone 4", 152),
    Z5("Zone 5", 171);

    /** Exclusive upper bound (the next zone's lower bound), or null for [Z5]. */
    val upperBpm: Int?
        get() = entries.getOrNull(ordinal + 1)?.lowerBpm

    companion object {
        /** The zone [bpm] falls into; anything below [Z2] (or negative) is [Z1]. */
        fun of(bpm: Int): HeartRateZone = entries.lastOrNull { bpm >= it.lowerBpm } ?: Z1
    }
}
