package dev.galaxybigbird.apartmentfinder.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exact 1:1 Kotlin port of Swift ApartmentCore LocalSearch.
 */
object LocalSearch {
    // A locality suffix needs a delimiter or state/ZIP context; city words can be street names.
    // Longer multi-word names (North Las Vegas / N Las Vegas) come before Las Vegas so "North" is never left dangling.
    val localitySuffixRegex = Regex(
        """(?i)(?:[,\.\r\n]+\s*(?:north\s+las\s+vegas|n\.?\s+las\s+vegas|las\s+vegas|henderson|boulder\s+city|blue\s+diamond|nellis\s+afb|spring\s+valley|sunrise\s+manor|enterprise|paradise|whitney|winchester|summerlin(?:\s+south)?)(?:[,\s.]+(?:nv|nevada))?(?:[,\s.]+\d{5}(?:-\d{4})?)?|[,\s.]+(?:north\s+las\s+vegas|n\.?\s+las\s+vegas|las\s+vegas|henderson|boulder\s+city|blue\s+diamond|nellis\s+afb|spring\s+valley|sunrise\s+manor|enterprise|paradise|whitney|winchester|summerlin(?:\s+south)?)[,\s.]+(?:(?:nv|nevada)(?:[,\s.]+\d{5}(?:-\d{4})?)?|\d{5}(?:-\d{4})?)|[,\s.]+(?:nv|nevada)(?:[,\s.]+\d{5}(?:-\d{4})?)?)[,\s.]*$"""
    )
    private val trailingPostalRegex = Regex("""\s+\d{5}(-\d{4})?$""")
    private val nonAlnumRegex = Regex("""[^a-z0-9]+""")
    private val diacriticsRegex = Regex("""\p{InCombiningDiacriticalMarks}+""")

    private val streetAliases = mapOf(
        "west" to "w",
        "east" to "e",
        "north" to "n",
        "south" to "s",
        "road" to "rd",
        "street" to "st",
        "avenue" to "ave",
        "drive" to "dr",
        "lane" to "ln",
        "boulevard" to "blvd",
        "court" to "ct",
        "way" to "wy",
        "parkway" to "pkwy"
    )

    fun normalized(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return diacriticsRegex.replace(decomposed, "").trim().lowercase(Locale.US)
    }

    fun addressKey(address: String, stripPostalCode: Boolean = true): String {
        var text = localitySuffixRegex.replace(normalized(address), "")
            .substringBefore(",")
        if (stripPostalCode) {
            text = trailingPostalRegex.replace(text, "")
        }
        return nonAlnumRegex.split(text)
            .filter { it.isNotEmpty() }
            .joinToString(" ") { streetAliases[it] ?: it }
    }

    fun locations(address: String, unit: String, building: String = "", places: List<Place>): List<Place> {
        val parsed = DeliveryAddress.resolve(
            address,
            suppliedUnit = unit,
            knownAddresses = places.map { it.address }.distinct()
        )
        if (parsed.hasConflictingUnit) return emptyList()
        val key = addressKey(parsed.address)
        val unitKey = normalized(parsed.unit)
        val buildingKey = normalized(building)
        if (key.isEmpty()) return emptyList()
        val priority = mapOf(
            Verification.FIELD_VERIFIED to 0,
            Verification.SOURCE_MAPPED to 1,
            Verification.ESTIMATED to 2
        )
        return places.filter {
            addressKey(it.address) == key &&
                (unitKey.isEmpty() || normalized(it.unit) == unitKey) &&
                (buildingKey.isEmpty() || normalized(it.building) == buildingKey)
        }.sortedWith(compareBy<Place>({ priority[it.verification] ?: 2 }, { it.id }))
    }

    fun merged(seed: List<Place>, overrides: List<Place>): List<Place> {
        val map = LinkedHashMap<String, Place>(seed.size + overrides.size)
        for (p in seed) map[p.id] = p
        for (p in overrides) map[p.id] = p
        return map.values.sortedBy { it.id }
    }

    fun haversineMeters(a: Coordinate, b: Coordinate): Double {
        val r = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2.0 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun bearingDegrees(from: Coordinate, to: Coordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
