package dev.galaxybigbird.apartmentfinder.core

import java.util.Locale

/**
 * Exact 1:1 Kotlin port of Swift ApartmentCore DeliveryAddress.
 * Splits apartment identifiers only when explicit, or following a known street.
 */
data class DeliveryAddress(
    val address: String,
    val unit: String,
    val hasConflictingUnit: Boolean
) {
    private data class KnownStreet(
        val address: String,
        val key: String,
        val length: Int
    )

    class Resolver(knownAddresses: Collection<String>) {
        private val known: List<KnownStreet> = knownAddresses
            .toSet()
            .map { addr ->
                val k = LocalSearch.addressKey(addr)
                KnownStreet(addr, k, k.length)
            }
            .sortedWith(compareByDescending<KnownStreet> { it.length }.thenBy { it.address })

        private val uniqueDirectionalFallback: List<KnownStreet> = run {
            val exactKeys = known.map { it.key }.toSet()
            val byStripped = LinkedHashMap<String, MutableList<KnownStreet>>()
            for (entry in known) {
                val stripped = LocalSearch.directionalStrippedKey(entry.key)
                if (stripped != null && stripped !in exactKeys) {
                    byStripped.getOrPut(stripped) { mutableListOf() }.add(entry)
                }
            }
            byStripped.mapNotNull { (stripped, group) ->
                val distinctKeys = group.map { it.key }.toSet()
                val first = group.firstOrNull()
                if (distinctKeys.size == 1 && first != null) {
                    KnownStreet(first.address, stripped, stripped.length)
                } else {
                    null
                }
            }.sortedWith(compareByDescending<KnownStreet> { it.length }.thenBy { it.address })
        }

        fun resolve(input: String, suppliedUnit: String = ""): DeliveryAddress =
            resolveInternal(input, suppliedUnit, known, uniqueDirectionalFallback)
    }

    companion object {
        private val fullStreetSuffixes = mapOf(
            "rd" to "road", "st" to "street", "ave" to "avenue", "dr" to "drive",
            "ln" to "lane", "blvd" to "boulevard", "ct" to "court", "wy" to "way", "pkwy" to "parkway",
            "hwy" to "highway", "cir" to "circle", "pl" to "place", "ter" to "terrace", "trl" to "trail",
            "pt" to "point", "cv" to "cove", "sq" to "square"
        )
        private val explicitUnitPattern = Regex(
            """(?i)(?:[,\s]+(?:apt|apartment|unit|suite|ste)\.?\s*|[,\s]*#\s*)([a-z0-9][a-z0-9-]*)$"""
        )
        private val stripUnitPrefixPattern = Regex(
            """(?i)^[,\s]*(?:(?:apt|apartment|unit|suite|ste)\.?\s*|#\s*)"""
        )
        private val unitTokenRegex = Regex("""^[a-z0-9]*[0-9][a-z0-9]*$""")
        private val digitsOnlyRegex = Regex("""^[0-9]+$""")

        fun resolve(input: String, suppliedUnit: String = "", knownAddresses: Collection<String>): DeliveryAddress =
            Resolver(knownAddresses).resolve(input, suppliedUnit)

        private fun resolveInternal(
            input: String,
            suppliedUnit: String,
            known: List<KnownStreet>,
            fallback: List<KnownStreet>
        ): DeliveryAddress {
            var street = input.trim()
            var embedded = ""
            val locMatch = LocalSearch.localitySuffixRegex.find(street)
            val hadLocality = locMatch != null
            val withoutLocality = (if (locMatch != null) street.substring(0, locMatch.range.first) else street)
                .trim { it == ' ' || it == ',' || it == '.' || it == '\t' || it == '\r' || it == '\n' }

            val unitMatch = explicitUnitPattern.find(withoutLocality)
            if (unitMatch != null) {
                val suffix = unitMatch.value
                embedded = stripUnitPrefixPattern.replace(suffix, "")
                street = withoutLocality.substring(0, unitMatch.range.first).trim()
            }

            val keySource = (if (embedded.isEmpty()) withoutLocality else street).replace(",", " ")
            val key = LocalSearch.addressKey(keySource, stripPostalCode = !hadLocality)

            fun matchIn(candidates: List<KnownStreet>): Boolean {
                for (entry in candidates) {
                    val candidateKey = entry.key
                    if (candidateKey.isEmpty()) continue
                    if (key == candidateKey) {
                        street = entry.address
                        return true
                    }
                    if (embedded.isEmpty() && key.startsWith("$candidateKey ")) {
                        val suffix = key.substring(entry.length + 1)
                        val units = suffix.split(" ").filter { it.isNotEmpty() }
                        val first = units.firstOrNull()
                        if (first != null && units.all { it == first } && unitTokenRegex.matches(first)) {
                            street = entry.address
                            embedded = first.uppercase(Locale.US)
                            return true
                        }
                    }
                    if (embedded.isEmpty()) {
                        val ending = candidateKey.substringAfterLast(' ', "")
                        val fullSuffix = fullStreetSuffixes[ending]
                        if (fullSuffix != null) {
                            val fullKey = candidateKey.dropLast(ending.length) + fullSuffix
                            val matchedPrefix = listOf(candidateKey, fullKey).firstOrNull { prefix ->
                                key.startsWith(prefix) && digitsOnlyRegex.matches(key.substring(prefix.length))
                            }
                            if (matchedPrefix != null) {
                                street = entry.address
                                embedded = key.substring(matchedPrefix.length)
                                return true
                            }
                        }
                    }
                }
                return false
            }

            if (!matchIn(known)) {
                matchIn(fallback)
            }

            val explicit = suppliedUnit.trim()
            val hasConflict = explicit.isNotEmpty() && embedded.isNotEmpty() &&
                LocalSearch.normalized(explicit) != LocalSearch.normalized(embedded)
            return DeliveryAddress(
                address = street,
                unit = explicit.ifEmpty { embedded },
                hasConflictingUnit = hasConflict
            )
        }
    }
}
