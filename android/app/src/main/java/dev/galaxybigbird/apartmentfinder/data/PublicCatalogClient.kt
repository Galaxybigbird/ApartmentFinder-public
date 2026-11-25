package dev.galaxybigbird.apartmentfinder.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.galaxybigbird.apartmentfinder.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Live Wave 35+ public catalog update client for Galaxybigbird/ApartmentFinder-public (`catalog-data`).
 * Uses strict HTTPS host checks, dual SHA-256 verification (compressed + uncompressed), and
 * zero-OOM streaming GZIP + Gson JsonReader directly into SQLite (peak RAM < 30 MB for 348,770+ features!).
 */
class PublicCatalogClient(private val context: Context) {

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLatestManifest(): PublicCatalogManifest = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_MANIFEST_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(req).execute().use { resp ->
            require(resp.code == 200) { "Catalog feed returned HTTP ${resp.code}." }
            val bytes = resp.body?.bytes() ?: error("Empty catalog manifest.")
            require(bytes.size in 1..16_384) { "Catalog manifest exceeds 16 KB limit." }
            parseManifest(bytes.toString(Charsets.UTF_8))
        }
    }

    suspend fun downloadAndInstall(
        manifest: PublicCatalogManifest,
        db: CatalogDatabase,
        onProgress: (stage: String, fraction: Float) -> Unit
    ): InstalledCatalogInfo = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "catalog_staging_${manifest.revision}")
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        cacheDir.mkdirs()

        try {
            val catDesc = manifest.files["public-catalog.json"]
                ?: error("Manifest missing public-catalog.json")
            val locDesc = manifest.files["public-locations.geojson"]
                ?: error("Manifest missing public-locations.geojson")
            val pathDesc = manifest.files["public-paths.geojson"]
                ?: error("Manifest missing public-paths.geojson")

            // 1. Download compressed (v2) or raw (v1) files to cache with exact compressed SHA-256 verification
            onProgress("Downloading community directory…", 0.08f)
            val catFile = downloadVerifiedFile(manifest, "public-catalog.json", catDesc, cacheDir)

            onProgress("Downloading 348k+ precision apartment pins…", 0.22f)
            val locFile = downloadVerifiedFile(manifest, "public-locations.geojson", locDesc, cacheDir) { dlFrac ->
                onProgress("Downloading pins (${(dlFrac * 100).toInt()}%)…", 0.22f + dlFrac * 0.28f)
            }

            onProgress("Verifying paths file…", 0.52f)
            val pathFile = downloadVerifiedFile(manifest, "public-paths.geojson", pathDesc, cacheDir)
            verifyUncompressedDigestOnly(pathFile, manifest.schemaVersion == 2, pathDesc)

            // 2. Stream-parse directly into SQLite inside ONE atomic transaction!
            db.IngestSession(manifest.revision, manifest.sequence).use { session ->
                onProgress("Indexing Las Vegas Valley communities…", 0.56f)
                streamCatalogIntoDb(catFile, manifest.schemaVersion == 2, catDesc, session)

                onProgress("Indexing precision pins & wall frontages…", 0.65f)
                streamLocationsIntoDb(locFile, manifest.schemaVersion == 2, locDesc, session) { count ->
                    val frac = (0.65f + (count.toFloat() / 360_000f).coerceAtMost(0.32f))
                    onProgress("Indexed %,d precision pins…".format(count), frac)
                }

                onProgress("Finalizing local pin database…", 0.98f)
                val info = session.commit()
                onProgress("Ready (${info.revision})", 1.0f)
                info
            }
        } finally {
            runCatching { cacheDir.deleteRecursively() }
        }
    }

    private fun downloadVerifiedFile(
        manifest: PublicCatalogManifest,
        name: String,
        desc: PublicCatalogFileDescriptor,
        dir: File,
        onFraction: ((Float) -> Unit)? = null
    ): File {
        val isGz = manifest.schemaVersion == 2
        val wireName = if (isGz) "$name.gz" else name
        val url = "$REVISIONS_BASE_URL/${manifest.revision}/$wireName"
        val expectedBytes = if (isGz) (desc.compressedBytes ?: error("Missing compressedBytes")) else desc.bytes
        val expectedSha = if (isGz) (desc.compressedSHA256 ?: error("Missing compressedSHA256")) else desc.sha256

        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json, application/geo+json, application/octet-stream")
            .header("Cache-Control", "no-cache")
            .build()
        val dest = File(dir, wireName)
        http.newCall(req).execute().use { resp ->
            require(resp.code == 200) { "Failed to download $wireName (HTTP ${resp.code})." }
            val bodyStream = resp.body?.byteStream() ?: error("Empty response for $wireName")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(dest).use { fos ->
                val buf = ByteArray(32_768)
                var n = bodyStream.read(buf)
                while (n >= 0) {
                    if (n > 0) {
                        total += n
                        require(total <= expectedBytes) { "$wireName exceeded manifest byte budget." }
                        md.update(buf, 0, n)
                        fos.write(buf, 0, n)
                        if (onFraction != null && expectedBytes > 0) {
                            onFraction(total.toFloat() / expectedBytes.toFloat())
                        }
                    }
                    n = bodyStream.read(buf)
                }
            }
            require(total == expectedBytes) { "$wireName truncated ($total vs $expectedBytes B)." }
            val actualSha = md.digest().toHex()
            require(actualSha == expectedSha) { "$wireName SHA-256 mismatch." }
        }
        return dest
    }

    private fun verifyUncompressedDigestOnly(file: File, isGz: Boolean, desc: PublicCatalogFileDescriptor) {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        openDecompressedStream(file, isGz).use { input ->
            val buf = ByteArray(32_768)
            var n = input.read(buf)
            while (n >= 0) {
                if (n > 0) {
                    total += n
                    md.update(buf, 0, n)
                }
                n = input.read(buf)
            }
        }
        require(total == desc.bytes && md.digest().toHex() == desc.sha256) {
            "Uncompressed paths payload failed integrity check."
        }
    }

    private fun openDecompressedStream(file: File, isGz: Boolean): InputStream {
        val raw = BufferedInputStream(FileInputStream(file), 65_536)
        return if (isGz) GZIPInputStream(raw, 65_536) else raw
    }

    private fun streamCatalogIntoDb(
        file: File,
        isGz: Boolean,
        desc: PublicCatalogFileDescriptor,
        session: CatalogDatabase.IngestSession
    ) {
        val md = MessageDigest.getInstance("SHA-256")
        val counting = CountingInputStream(DigestInputStream(openDecompressedStream(file, isGz), md))
        JsonReader(InputStreamReader(counting, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "communities" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            readCommunityObject(reader)?.let { session.insertCommunity(it) }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            // Drain any trailing whitespace/newline for exact SHA-256
            val drain = ByteArray(1024)
            while (counting.read(drain) >= 0) { /* drain */ }
        }
        require(counting.bytesRead == desc.bytes && md.digest().toHex() == desc.sha256) {
            "public-catalog.json failed uncompressed SHA-256 verification."
        }
    }

    private fun readCommunityObject(reader: JsonReader): Community? {
        var id = ""
        var name = ""
        var address = ""
        var zip = ""
        val deliveryAddresses = mutableListOf<String>()
        val aliases = mutableListOf<String>()
        var researchStatus = ""
        val gapNotes = mutableListOf<String>()
        var lat: Double? = null
        var lon: Double? = null
        var locker: DigitalLockerEvidence? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "name" -> name = reader.nextString()
                "address" -> address = reader.nextString()
                "zip" -> zip = reader.nextString()
                "deliveryAddresses" -> readStringArrayInto(reader, deliveryAddresses)
                "aliases" -> readStringArrayInto(reader, aliases)
                "researchStatus" -> researchStatus = reader.nextString()
                "gapNotes" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        readStringArrayInto(reader, gapNotes)
                    } else if (reader.peek() == JsonToken.STRING) {
                        gapNotes.add(reader.nextString())
                    } else {
                        reader.skipValue()
                    }
                }
                "latitude" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else lat = reader.nextDouble()
                "longitude" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else lon = reader.nextDouble()
                "digitalLocker" -> {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull()
                    else locker = parseLocker(JsonParser.parseReader(reader))
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (id.isBlank()) return null
        return Community(
            id = id,
            name = name,
            address = address,
            zip = zip,
            deliveryAddresses = deliveryAddresses,
            aliases = aliases,
            researchStatus = researchStatus,
            gapNotes = gapNotes,
            coordinate = if (lat != null && lon != null) Coordinate(lat, lon) else null,
            digitalLocker = locker
        )
    }

    private fun parseLocker(el: JsonElement): DigitalLockerEvidence? {
        if (!el.isJsonObject) return null
        val o = el.asJsonObject
        val status = o.get("status")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val brand = o.get("brand")?.takeIf { it.isJsonPrimitive }?.asString
        val desc = o.get("placementDescription")?.takeIf { it.isJsonPrimitive }?.asString
        val ptObj = o.get("point")?.takeIf { it.isJsonObject }?.asJsonObject
        val pt = ptObj?.let {
            val la = it.get("latitude")?.asDouble
            val lo = it.get("longitude")?.asDouble
            val prec = it.get("precision")?.takeIf { p -> p.isJsonPrimitive }?.asString.orEmpty()
            if (la != null && lo != null) DigitalLockerPoint(Coordinate(la, lo), prec) else null
        }
        return DigitalLockerEvidence(status = status, brand = brand, placementDescription = desc, point = pt)
    }

    private fun streamLocationsIntoDb(
        file: File,
        isGz: Boolean,
        desc: PublicCatalogFileDescriptor,
        session: CatalogDatabase.IngestSession,
        onCount: (Int) -> Unit
    ) {
        val md = MessageDigest.getInstance("SHA-256")
        val counting = CountingInputStream(DigestInputStream(openDecompressedStream(file, isGz), md))
        var count = 0
        JsonReader(InputStreamReader(counting, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "features" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            readFeatureAndInsert(reader, session)
                            count += 1
                            if (count % 15_000 == 0) onCount(count)
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val drain = ByteArray(1024)
            while (counting.read(drain) >= 0) { /* drain */ }
        }
        require(counting.bytesRead == desc.bytes && md.digest().toHex() == desc.sha256) {
            "public-locations.geojson failed uncompressed SHA-256 verification."
        }
    }

    private fun readFeatureAndInsert(reader: JsonReader, session: CatalogDatabase.IngestSession) {
        var id = ""
        var lon = Double.NaN
        var lat = Double.NaN
        var communityId = ""
        var address = ""
        var building = ""
        var unit = ""
        var floor = ""
        var kindStr = "unitPosition"
        var verStr = "sourceMapped"
        var notes = ""
        var identityUncertain = false
        var referenceKind: String? = null
        var sourceUnitId: String? = null
        var eseJson: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "geometry" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "coordinates" -> {
                                reader.beginArray()
                                if (reader.hasNext()) lon = reader.nextDouble()
                                if (reader.hasNext()) lat = reader.nextDouble()
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "properties" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "communityID" -> communityId = nextStringOrEmpty(reader)
                            "address" -> address = nextStringOrEmpty(reader)
                            "building" -> building = nextStringOrEmpty(reader)
                            "unit" -> unit = nextStringOrEmpty(reader)
                            "floor" -> floor = nextStringOrEmpty(reader)
                            "kind" -> kindStr = nextStringOrEmpty(reader)
                            "verification" -> verStr = nextStringOrEmpty(reader)
                            "notes" -> notes = nextStringOrEmpty(reader)
                            "identityUncertain" -> {
                                if (reader.peek() == JsonToken.BOOLEAN) identityUncertain = reader.nextBoolean()
                                else reader.skipValue()
                            }
                            "referenceKind" -> referenceKind = nextNullableString(reader)
                            "sourceUnitID" -> sourceUnitId = nextNullableString(reader)
                            "entranceSideEstimate" -> {
                                if (reader.peek() == JsonToken.NULL) reader.nextNull()
                                else eseJson = JsonParser.parseReader(reader).toString()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (id.isNotEmpty() && communityId.isNotEmpty() && lat.isFinite() && lon.isFinite()) {
            val place = Place(
                id = id,
                communityId = communityId,
                address = address,
                building = building,
                unit = unit,
                floor = floor,
                kind = LocationKind.fromWire(kindStr),
                verification = Verification.fromWire(verStr),
                coordinate = Coordinate(lat, lon),
                notes = notes,
                identityUncertain = identityUncertain,
                referenceKind = referenceKind,
                sourceUnitId = sourceUnitId
            )
            session.insertPlace(place, eseJson)
        }
    }

    private fun readStringArrayInto(reader: JsonReader, target: MutableList<String>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.STRING) target.add(reader.nextString())
            else reader.skipValue()
        }
        reader.endArray()
    }

    private fun nextStringOrEmpty(reader: JsonReader): String =
        if (reader.peek() == JsonToken.STRING) reader.nextString() else {
            reader.skipValue(); ""
        }

    private fun nextNullableString(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.STRING) reader.nextString() else {
            reader.skipValue(); null
        }

    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val v = delegate.read()
            if (v >= 0) bytesRead += 1
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }

        override fun close() = delegate.close()
    }

    companion object {
        const val LATEST_MANIFEST_URL =
            "https://raw.githubusercontent.com/Galaxybigbird/ApartmentFinder-public/catalog-data/latest-v3.json"
        private const val REVISIONS_BASE_URL =
            "https://raw.githubusercontent.com/Galaxybigbird/ApartmentFinder-public/catalog-data/revisions"

        private val revisionRegex = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$""")
        private val digestRegex = Regex("""^[0-9a-f]{64}$""")
        private val requiredFiles = setOf("public-catalog.json", "public-locations.geojson", "public-paths.geojson")

        fun parseManifest(jsonText: String): PublicCatalogManifest {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val schema = root.get("schemaVersion")?.asInt ?: error("Missing schemaVersion")
            require(schema in 1..2) { "Unsupported schemaVersion $schema" }
            val revision = root.get("revision")?.asString ?: error("Missing revision")
            require(revisionRegex.matches(revision)) { "Unsafe revision $revision" }
            val sequence = root.get("sequence")?.asLong ?: error("Missing sequence")
            require(sequence >= 1) { "Invalid sequence $sequence" }
            val packVersion = root.get("packVersion")?.asString ?: error("Missing packVersion")
            val filesObj = root.getAsJsonObject("files") ?: error("Missing files")
            require(filesObj.keySet() == requiredFiles) { "Unexpected catalog file set" }

            val files = LinkedHashMap<String, PublicCatalogFileDescriptor>()
            for (name in requiredFiles) {
                val entry = filesObj.getAsJsonObject(name)
                val bytes = entry.get("bytes").asLong
                val sha = entry.get("sha256").asString
                require(bytes in 1..256_000_000L && digestRegex.matches(sha)) { "Invalid metadata for $name" }
                if (schema == 2) {
                    val enc = entry.get("encoding")?.asString
                    val gzBytes = entry.get("compressedBytes")?.asLong ?: error("Missing compressedBytes")
                    val gzSha = entry.get("compressedSHA256")?.asString ?: error("Missing compressedSHA256")
                    require(enc == "gzip" && gzBytes in 1..64_000_000L && digestRegex.matches(gzSha)) {
                        "Invalid gzip metadata for $name"
                    }
                    files[name] = PublicCatalogFileDescriptor(bytes, sha, gzBytes, gzSha, enc)
                } else {
                    files[name] = PublicCatalogFileDescriptor(bytes, sha)
                }
            }
            return PublicCatalogManifest(schema, revision, sequence, packVersion, files)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
