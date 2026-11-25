package dev.galaxybigbird.apartmentfinder

import dev.galaxybigbird.apartmentfinder.core.*
import dev.galaxybigbird.apartmentfinder.data.PublicCatalogClient
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreParityTests {

    @Test
    fun localSearchAddressKeyNormalizesLocalityAndStreetAliases() {
        assertEquals(
            "7960 rafael rivera wy",
            LocalSearch.addressKey("7960 Rafael Rivera Way, Las Vegas, NV 89113")
        )
        assertEquals(
            "8341 w warm springs rd",
            LocalSearch.addressKey("8341 West Warm Springs Road, Las Vegas, Nevada 89113-1234")
        )
        assertEquals(
            "4425 w rome blvd",
            LocalSearch.addressKey("4425 W. Rome Boulevard, North Las Vegas, NV 89084")
        )
        assertEquals(
            "200 s green valley pkwy",
            LocalSearch.addressKey("200 South Green Valley Parkway, Henderson, NV 89012")
        )
    }

    @Test
    fun deliveryAddressSplitsExplicitKnownRepeatedAndJoinedUnits() {
        val resolver = DeliveryAddress.Resolver(
            listOf("9400 Bermuda Rd", "4425 W Rome Blvd", "8475 Michael Cherry Ave")
        )

        val explicit = resolver.resolve("4425 W Rome Blvd Apt 111, North Las Vegas, NV 89084")
        assertEquals("4425 W Rome Blvd", explicit.address)
        assertEquals("111", explicit.unit)
        assertFalse(explicit.hasConflictingUnit)

        val knownSuffix = resolver.resolve("4425 W Rome Blvd 111, North Las Vegas, NV 89084")
        assertEquals("4425 W Rome Blvd", knownSuffix.address)
        assertEquals("111", knownSuffix.unit)

        val repeated = resolver.resolve("4425 W Rome Blvd 111 111")
        assertEquals("4425 W Rome Blvd", repeated.address)
        assertEquals("111", repeated.unit)

        val joined = resolver.resolve("9400 Bermuda RD255, Las Vegas, NV 89123")
        assertEquals("9400 Bermuda Rd", joined.address)
        assertEquals("255", joined.unit)

        val conflict = resolver.resolve("4425 W Rome Blvd #111", suppliedUnit = "222")
        assertTrue(conflict.hasConflictingUnit)
    }

    @Test
    fun routeCsvParserSupportsOnTracSubStopsAndStandardCsv() {
        val onTrac = """
            Barcode,Last Event,Last Event Time,Last GPS Location,Seq No,Address
            BC1,OFD,09:00,,12.2,"4425 W Rome Blvd 204, North Las Vegas, NV 89084"
            BC2,OFD,09:00,,12.1,"4425 W Rome Blvd 111, North Las Vegas, NV 89084"
            BC3,OFD,09:00,,5,"9400 Bermuda RD255, Las Vegas, NV 89123"
        """.trimIndent()

        val parsed = RouteCsvParser.parse(
            onTrac,
            knownAddresses = listOf("4425 W Rome Blvd", "9400 Bermuda Rd")
        )
        assertTrue(parsed.canImport)
        assertEquals(3, parsed.stops.size)
        val ordered = parsed.stops.sortedBy { it.sequence }
        assertEquals("5", ordered[0].sourceStopLabel)
        assertEquals("255", ordered[0].unit)
        assertEquals("12.1", ordered[1].sourceStopLabel)
        assertEquals("111", ordered[1].unit)
        assertEquals("12.2", ordered[2].sourceStopLabel)
        assertEquals("204", ordered[2].unit)

        val standard = """
            sequence,address,unit,notes
            1,8341 W Warm Springs Rd,2104,Gate #1234
            2,8341 W Warm Springs Rd,1102,
        """.trimIndent()
        val stdParsed = RouteCsvParser.parse(standard)
        assertTrue(stdParsed.canImport)
        assertEquals(2, stdParsed.stops.size)
        assertEquals("Gate #1234", stdParsed.stops[0].notes)
    }

    @Test
    fun manifestReaderSortsZipNewestFirstAndRejectsTraversal() {
        val csvA = "sequence,address,unit,notes\n1,8341 W Warm Springs Rd,101,\n"
        val csvB = "sequence,address,unit,notes\n1,8341 W Warm Springs Rd,202,\n"

        val goodZip = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("manifests/2026-11-23.csv"))
                zos.write(csvA.toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("manifests/2026-11-24.csv"))
                zos.write(csvB.toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val docs = ManifestReader.read(goodZip, "ontrac.zip")
        assertEquals(2, docs.size)
        assertEquals("manifests/2026-11-24.csv", docs[0].name)
        assertEquals("202", docs[0].result.stops[0].unit)

        val evilZip = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("../escape.csv"))
                zos.write(csvA.toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManifestReader.read(evilZip, "evil.zip")
        }
    }

    @Test
    fun routePlannerGroupsEarliestStopFirstAndPrefersVerifiedDoorOverEstimatedPin() {
        val c1 = Community("vestra", "Vestra", "8475 Michael Cherry Ave", "89113", coordinate = Coordinate(36.0630, -115.2775))
        val c2 = Community("core", "Core", "8341 W Warm Springs Rd", "89113", coordinate = Coordinate(36.0542, -115.2729))

        val stops = listOf(
            DeliveryStop(id = "s1", sequence = 10, address = "8475 Michael Cherry Ave", unit = "1-113"),
            DeliveryStop(id = "s2", sequence = 4, address = "8341 W Warm Springs Rd", unit = "205"),
            DeliveryStop(id = "s3", sequence = 7, address = "999 Unknown St", unit = ""),
            DeliveryStop(id = "s4", sequence = 8, address = "123 House Ln", unit = "")
        )
        val rules = listOf(ApartmentAddressRule(address = "123 House Ln", kind = AddressRuleKind.NON_APARTMENT))
        val plan = ApartmentRoutePlanner.buildPlan(stops, listOf(c1, c2), rules)

        assertEquals(2, plan.groups.size)
        assertEquals("core", plan.groups[0].id) // earliest stop seq 4 first!
        assertEquals("vestra", plan.groups[1].id)
        assertEquals(1, plan.unmatched.size)
        assertEquals(1, plan.excluded.size)

        val estPlace = Place(
            id = "vestra-unit-1113",
            communityId = "vestra",
            address = "8475 Michael Cherry Ave",
            building = "1",
            unit = "1-113",
            kind = LocationKind.UNIT_POSITION,
            verification = Verification.ESTIMATED,
            coordinate = Coordinate(36.063065, -115.277601),
            identityUncertain = true,
            entranceSideEstimate = EntranceSideEstimate(
                frontage = listOf(Coordinate(36.063078, -115.277653), Coordinate(36.063077, -115.277529)),
                workingAllowanceMeters = 6.0
            )
        )
        assertTrue(estPlace.highlightZoneRadiusMeters(completed = false, ready = false) > 6.0)

        // Before checking door: CONFIRM_PIN
        val estMatch = ApartmentRoutePlanner.resolveDoorMatch(stops[0], "vestra", listOf(estPlace))
        assertNull(estMatch.readyDoor)
        assertEquals(DoorMatchIssue.CONFIRM_PIN, estMatch.issue)

        // After owner confirms checked door: readyDoor overrides uncertain estimate!
        val checkedDoor = estPlace.confirmedDoor(Coordinate(36.063070, -115.277605), "1", "1")
        val verifiedMatch = ApartmentRoutePlanner.resolveDoorMatch(stops[0], "vestra", listOf(estPlace, checkedDoor))
        assertEquals(checkedDoor.id, verifiedMatch.readyDoor?.id)
        assertNull(verifiedMatch.issue)
        assertEquals(6.5, checkedDoor.highlightZoneRadiusMeters(completed = false, ready = true), 0.01)

        // Next apartment picks nearest by fresh GPS
        val fixNearVestra = UserLocationFix(Coordinate(36.0631, -115.2774), 5f, null, System.currentTimeMillis())
        val choice = ApartmentRoutePlanner.selectNextApartment(plan.groups, currentGroupId = "core", fix = fixNearVestra)
        assertNotNull(choice)
        assertEquals("vestra", choice!!.group.id)
        assertTrue(choice.isNearestByGps)
    }

    @Test
    fun publicCatalogClientParsesWave35SchemaV2ManifestAndRejectsBadMetadata() {
        val hex = "a".repeat(64)
        val validJson = """
            {
              "schemaVersion": 2,
              "revision": "2026.11.24.35",
              "sequence": 295,
              "packVersion": "1.0.0",
              "files": {
                "public-catalog.json": {
                  "bytes": 1000, "sha256": "$hex",
                  "compressedBytes": 400, "compressedSHA256": "$hex", "encoding": "gzip"
                },
                "public-locations.geojson": {
                  "bytes": 219500000, "sha256": "$hex",
                  "compressedBytes": 5000000, "compressedSHA256": "$hex", "encoding": "gzip"
                },
                "public-paths.geojson": {
                  "bytes": 500, "sha256": "$hex",
                  "compressedBytes": 200, "compressedSHA256": "$hex", "encoding": "gzip"
                }
              }
            }
        """.trimIndent()

        val manifest = PublicCatalogClient.parseManifest(validJson)
        assertEquals(2, manifest.schemaVersion)
        assertEquals("2026.11.24.35", manifest.revision)
        assertEquals(295L, manifest.sequence)
        assertEquals(3, manifest.files.size)

        val unsafeRevision = validJson.replace("2026.11.24.35", "../evil")
        assertThrows(IllegalArgumentException::class.java) {
            PublicCatalogClient.parseManifest(unsafeRevision)
        }
    }
}
