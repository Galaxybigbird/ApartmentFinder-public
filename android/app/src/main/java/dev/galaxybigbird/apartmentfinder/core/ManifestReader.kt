package dev.galaxybigbird.apartmentfinder.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

data class ManifestDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val result: RouteImport
)

/**
 * Exact Kotlin port of Swift ManifestReader: handles single CSV/TXT files and OnTrac multi-day ZIP exports
 * with strict size, entry-count, and path-traversal bounds.
 */
object ManifestReader {
    const val MAXIMUM_FILE_BYTES = 5_000_000
    private const val MAXIMUM_TOTAL_EXPANDED = 20_000_000
    private const val MAXIMUM_ZIP_ENTRIES = 366

    fun read(
        bytes: ByteArray,
        filename: String,
        knownAddresses: Collection<String> = emptyList()
    ): List<ManifestDocument> {
        require(bytes.size <= MAXIMUM_FILE_BYTES) { "Manifest is too large (maximum 5 MB)." }
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
        val looksLikeZip = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

        if (!looksLikeZip && (ext == "csv" || ext == "txt" || ext.isEmpty())) {
            return listOf(parseSingle(bytes, filename.ifBlank { "manifest.csv" }, knownAddresses))
        }
        require(ext == "zip" || looksLikeZip) {
            "Choose an OnTrac ZIP export or a CSV file."
        }

        val documents = mutableListOf<ManifestDocument>()
        var entryCount = 0
        var totalExpanded = 0L

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount += 1
                require(entryCount <= MAXIMUM_ZIP_ENTRIES) { "The ZIP contains too many files." }
                val normalizedPath = entry.name.replace('\\', '/')
                require(
                    !normalizedPath.startsWith("/") &&
                        !normalizedPath.contains(":") &&
                        !normalizedPath.split("/").contains("..")
                ) { "The ZIP contains an unsupported file path." }

                if (!entry.isDirectory &&
                    !normalizedPath.startsWith("__MACOSX/") &&
                    normalizedPath.lowercase(Locale.US).endsWith(".csv")
                ) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16_384)
                    var read = zis.read(buf)
                    while (read >= 0) {
                        if (read > 0) {
                            totalExpanded += read
                            require(out.size() + read <= MAXIMUM_FILE_BYTES && totalExpanded <= MAXIMUM_TOTAL_EXPANDED) {
                                "The expanded manifest is too large."
                            }
                            out.write(buf, 0, read)
                        }
                        read = zis.read(buf)
                    }
                    documents.add(parseSingle(out.toByteArray(), normalizedPath, knownAddresses))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        require(documents.isNotEmpty()) { "This ZIP contains no CSV manifests." }
        return documents.sortedByDescending { it.name }
    }

    private fun parseSingle(
        bytes: ByteArray,
        name: String,
        knownAddresses: Collection<String>
    ): ManifestDocument {
        return try {
            val text = bytes.toString(Charsets.UTF_8)
            ManifestDocument(name = name, result = RouteCsvParser.parse(text, knownAddresses))
        } catch (e: Exception) {
            ManifestDocument(
                name = name,
                result = RouteImport(stops = emptyList(), errors = listOf(e.message ?: "Could not read manifest."))
            )
        }
    }
}
