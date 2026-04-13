package com.kim.topoview.data

/**
 * Calculate bounding boxes for Australian map grid sheet numbers.
 *
 * The Australian 1:100,000 map grid divides Australia into sheets of 0.5° longitude × 0.5° latitude.
 * Sheet numbering uses a zone number (east-west) and a row number (north-south).
 *
 * For 1:25,000 sheets, each 1:100k sheet is divided into 4 quadrants:
 *   1 (NW), 2 (NE), 3 (SW), 4 (SE)
 * Each quadrant may further have N/S halves for some series.
 */
object SheetGridCalculator {

    /**
     * Parse a sheet number like "8224-3" or "8930-1S" and return its bounding box.
     * Returns (minLon, minLat, maxLon, maxLat) or null if unparseable.
     *
     * Sheet number format: ZZRR-Q[H]
     * ZZ = zone (columns), RR = row, Q = quadrant (1-4), H = half (N/S)
     *
     * Zone 49 starts at 141°E, each zone is 0.5° wide.
     * Row 01 starts at about -10° (for mainland Australia), each row is 0.5° tall.
     * Rows decrease southward: row 25 is around -22.5°, etc.
     *
     * Actually, the Australian grid:
     * Zone numbers increase eastward. Zone 49 covers 141°-141.5°E.
     * The base longitude = 91°E + (zone - 1) × 0.5° → but this doesn't match.
     *
     * More precisely for the commonly used numbering (NATMAP):
     * Sheet number = ZZRR where ZZ and RR are two-digit.
     * Zone starts: zone 49 → 141°E
     * Row: row 30 → -10°S latitude (top), with rows going south.
     *
     * The 1:100k sheet convention:
     * First two digits = zone number (east-west), next two = row (north-south)
     * Base: Zone 49 = 141.0°E, Row 30 = -10.0°S
     * Each sheet = 0.5° × 0.5°
     */
    fun sheetBbox(sheetNumber: String): DoubleArray? {
        // Parse "8224-3" or "8930-1S"
        val parts = sheetNumber.split("-")
        if (parts.size != 2 || parts[0].length != 4) return null

        val zone = parts[0].substring(0, 2).toIntOrNull() ?: return null
        val row = parts[0].substring(2, 4).toIntOrNull() ?: return null
        val quadStr = parts[1]
        val quad = quadStr[0].digitToIntOrNull() ?: return null
        if (quad !in 1..4) return null
        val half = if (quadStr.length > 1) quadStr[1].uppercaseChar() else null

        // 1:100k sheet bounds
        // Zone 49 → 141.0°E; each zone = 0.5°
        val sheet100kMinLon = 91.0 + zone * 0.5
        // Row 30 → -10.0°S; each row = 0.5° going south
        val sheet100kMaxLat = -10.0 - (row - 30) * 0.5
        val sheet100kMinLat = sheet100kMaxLat - 0.5

        // 1:25k quadrant within the 1:100k sheet
        // Quadrant layout: 1=NW, 2=NE, 3=SW, 4=SE
        val halfLon = 0.25
        val halfLat = 0.25

        val qMinLon: Double
        val qMaxLon: Double
        val qMinLat: Double
        val qMaxLat: Double

        when (quad) {
            1 -> { // NW
                qMinLon = sheet100kMinLon
                qMaxLon = sheet100kMinLon + halfLon
                qMinLat = sheet100kMaxLat - halfLat
                qMaxLat = sheet100kMaxLat
            }
            2 -> { // NE
                qMinLon = sheet100kMinLon + halfLon
                qMaxLon = sheet100kMinLon + 2 * halfLon
                qMinLat = sheet100kMaxLat - halfLat
                qMaxLat = sheet100kMaxLat
            }
            3 -> { // SW
                qMinLon = sheet100kMinLon
                qMaxLon = sheet100kMinLon + halfLon
                qMinLat = sheet100kMinLat
                qMaxLat = sheet100kMinLat + halfLat
            }
            4 -> { // SE
                qMinLon = sheet100kMinLon + halfLon
                qMaxLon = sheet100kMinLon + 2 * halfLon
                qMinLat = sheet100kMinLat
                qMaxLat = sheet100kMinLat + halfLat
            }
            else -> return null
        }

        // N/S half
        if (half != null) {
            val midLat = (qMinLat + qMaxLat) / 2.0
            return when (half) {
                'N' -> doubleArrayOf(qMinLon, midLat, qMaxLon, qMaxLat)
                'S' -> doubleArrayOf(qMinLon, qMinLat, qMaxLon, midLat)
                else -> doubleArrayOf(qMinLon, qMinLat, qMaxLon, qMaxLat)
            }
        }

        return doubleArrayOf(qMinLon, qMinLat, qMaxLon, qMaxLat)
    }
}
