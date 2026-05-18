package com.openardf.serialslinger.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignalSlingerFirmwareUpdateTest {
    @Test
    fun parsesReleaseManifest() {
        val manifest = SignalSlingerFirmwareUpdate.parseReleaseInfo("\uFEFF" + sampleManifest(sampleHex().encodeToByteArray()))

        assertEquals("signalslinger-release-info-v1", manifest.format)
        assertEquals("SignalSlinger", manifest.product)
        assertEquals("2.0.0", manifest.version)
        assertEquals("HW-3.5", manifest.board)
        assertEquals("SignalSlinger-Update-v2.0.0-HW-3.5.hex", manifest.update.fileName)
        assertEquals(0x4000, manifest.update.startAddress)
        assertEquals(4, manifest.update.bytesInImage)
        assertEquals(9_600, manifest.serialSlinger.appBaud)
        assertEquals(115_200, manifest.serialSlinger.updateBaud)
        assertEquals("INF", manifest.serialSlinger.appInfoCommand)
        assertEquals("UPD", manifest.serialSlinger.appUpdateCommand)
        assertEquals("U", manifest.serialSlinger.bootloaderEntryCommand)
        assertEquals(512, manifest.serialSlinger.pageBytes)
        assertEquals(1, manifest.serialSlinger.protocolVersion)
        assertEquals("BL0.11", manifest.serialSlinger.bootloaderVersion)
        assertEquals(0x4000, manifest.serialSlinger.appStartAddress)
        assertEquals(131_072, manifest.serialSlinger.flashBytes)
        assertEquals(manifest.update.fileName, manifest.updateFile().fileName)
    }

    @Test
    fun parsesReleaseManifestWithTwoKilobyteBootArea() {
        val manifest = SignalSlingerFirmwareUpdate.parseReleaseInfo(
            sampleManifest(
                updateBytes = sampleHex(appStartAddress = 0x2000).encodeToByteArray(),
                appStartAddress = 0x2000,
            ),
        )

        assertEquals(0x2000, manifest.update.startAddress)
        assertEquals(0x2000, manifest.serialSlinger.appStartAddress)
    }

    @Test
    fun parsesWorkshopSetupManifestFields() {
        val manifest = SignalSlingerFirmwareUpdate.parseReleaseInfo(
            sampleWorkshopManifest(),
        )

        assertEquals("SignalSlinger-First-Install-v2.0.2-HW-3.5.hex", manifest.firstInstall?.fileName)
        assertEquals("SignalSlinger-Setup-Helper-v2.0.2-HW-3.5-BL0.12.hex", manifest.workshopSetup?.setupHelperFileName)
        assertEquals("Prepare-SignalSlinger-Updates-v2.0.2-HW-3.5.ps1", manifest.workshopSetup?.setupLauncherFileName)
        assertEquals("provision-bootloader.ps1", manifest.workshopSetup?.provisioningScriptFileName)
        assertEquals("test-bootloader-serial.ps1", manifest.workshopSetup?.serialValidationScriptFileName)
        assertEquals(listOf("atmelice", "pickit4", "snap", "powerdebugger", "edbg", "medbg", "nedbg"), manifest.workshopSetup?.supportedProgrammers)
        assertEquals(16, manifest.workshopSetup?.bootSectionPages)
        assertEquals("0x10", manifest.workshopSetup?.fuseBootSize)
        assertEquals("0x00", manifest.workshopSetup?.fuseCodeSize)
        assertEquals("SignalSlinger-First-Install-v2.0.2-HW-3.5.hex", manifest.firstInstallFile().fileName)
        assertEquals("SignalSlinger-Setup-Helper-v2.0.2-HW-3.5-BL0.12.hex", manifest.setupHelperFile().fileName)
        assertEquals("Prepare-SignalSlinger-Updates-v2.0.2-HW-3.5.ps1", manifest.setupLauncherFile().fileName)
        assertEquals(2, manifest.workshopSetupToolFiles().size)
    }

    @Test
    fun gatesBootloaderUpdateSupportAtFirmwareTwoDotZeroDotZero() {
        assertEquals(false, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate(null))
        assertEquals(false, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate(""))
        assertEquals(false, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("1.2.2"))
        assertEquals(false, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("1.99.99"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("2.0.0"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("2.0"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("2.0.1"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("2.1.0-beta"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("3.0.0"))
    }

    @Test
    fun validatesUpdateFileSha256() {
        val bytes = "abc".encodeToByteArray()
        val releaseFile = SignalSlingerReleaseFile(
            fileName = "update.hex",
            kind = "update",
            purpose = null,
            sizeBytes = bytes.size,
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )

        SignalSlingerFirmwareUpdate.verifyReleaseFileHash(releaseFile, bytes)

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.verifyReleaseFileHash(releaseFile, "abd".encodeToByteArray())
        }
        assertTrue(error.message.orEmpty().contains("hash mismatch"))
    }

    @Test
    fun parsesAppInfoLines() {
        val info = SignalSlingerFirmwareUpdate.parseAppInfo(
            listOf(
                "startup text",
                "SignalSlINF* INF product=SignalSlinger update=UPD",
                "> INF* INF product=SignalSlinger update=UPD",
                "* INF sw=2.0.0 hw=3.5 app=0x4000 baud=115200",
                "* INF bl=BL0.13 proto=1",
            ),
        )

        assertNotNull(info)
        assertEquals("SignalSlinger", info.product)
        assertEquals("2.0.0", info.softwareVersion)
        assertEquals("3.5", info.hardwareBuild)
        assertEquals(0x4000, info.appStartAddress)
        assertEquals(115_200, info.updateBaud)
        assertEquals("UPD", info.appUpdateCommand)
        assertEquals("BL0.13", info.bootloaderVersion)
        assertEquals(1, info.bootloaderProtocolVersion)
        info.validateForRelease(SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray())))
    }

    @Test
    fun treatsUnknownAppInfoBootloaderVersionAsUnavailable() {
        val info = SignalSlingerFirmwareUpdate.parseAppInfo(
            listOf(
                "* INF product=SignalSlinger update=UPD",
                "* INF sw=2.0.0 hw=3.5 app=0x4000 baud=115200",
                "* INF bl=unknown proto=unknown",
            ),
        )

        assertNotNull(info)
        assertEquals(null, info.bootloaderVersion)
        assertEquals(null, info.bootloaderProtocolVersion)
    }

    @Test
    fun rejectsAppInfoForWrongHardware() {
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray()))
        val info = SignalSlingerAppInfo(
            product = "SignalSlinger",
            softwareVersion = "2.0.0",
            hardwareBuild = "3.4",
            appStartAddress = 0x4000,
            updateBaud = 115_200,
            appUpdateCommand = "UPD",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            info.validateForRelease(release)
        }
        assertTrue(error.message.orEmpty().contains("does not match package board"))
    }

    @Test
    fun canAllowAppInfoHardwareMismatchForOverrideUpdates() {
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray()))
        val info = SignalSlingerAppInfo(
            product = "SignalSlinger",
            softwareVersion = "2.0.0",
            hardwareBuild = "3.4",
            appStartAddress = 0x4000,
            updateBaud = 115_200,
            appUpdateCommand = "UPD",
        )

        info.validateForRelease(release, requireHardwareMatch = false)
    }

    @Test
    fun matchesHardwareBoardAliases() {
        assertTrue(SignalSlingerFirmwareUpdate.hardwareMatchesBoard("3.4", "HW-3.4"))
        assertTrue(SignalSlingerFirmwareUpdate.hardwareMatchesBoard("HW-3.4", "HW-3.4"))
        assertTrue(SignalSlingerFirmwareUpdate.hardwareMatchesBoard("Board-3.4", "HW-3.4"))
    }

    @Test
    fun postUpdateConfirmationStillRequiresPackageHardware() {
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray()))
        val info = SignalSlingerAppInfo(
            product = "SignalSlinger",
            softwareVersion = "2.0.0",
            hardwareBuild = "3.4",
            appStartAddress = 0x4000,
            updateBaud = 115_200,
            appUpdateCommand = "UPD",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            info.validateUpdatedForRelease(release)
        }
        assertTrue(error.message.orEmpty().contains("does not match package board"))
    }

    @Test
    fun fallsBackToVerInfoForUnsupportedOlderFirmware() {
        val info = SignalSlingerFirmwareUpdate.parseAppInfo(
            listOf("* SW Ver: 1.2.2 HW Build: 3.5"),
        )

        assertNotNull(info)
        assertEquals("1.2.2", info.softwareVersion)
        assertEquals("3.5", info.hardwareBuild)
        val error = assertFailsWith<IllegalArgumentException> {
            info.validateForRelease(SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray())))
        }
        assertTrue(error.message.orEmpty().contains("does not support firmware updates"))
    }

    @Test
    fun rejectsIntelHexDataBelowAppStart() {
        val hex = intelHex(
            0x0000 to byteArrayOf(0x12, 0x34),
            0x4000 to byteArrayOf(0x56),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.parseIntelHexPages(hex, appStartAddress = 0x4000)
        }
        assertTrue(error.message.orEmpty().contains("outside app flash"))
        assertTrue(error.message.orEmpty().contains("0x00000000"))
    }

    @Test
    fun rejectsIntelHexDataAtOrAboveFlashEnd() {
        val hex = intelHex(
            0x4000 to byteArrayOf(0x56),
            0x20000 to byteArrayOf(0x12),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.parseIntelHexPages(hex, appStartAddress = 0x4000)
        }
        assertTrue(error.message.orEmpty().contains("0x00020000"))
    }

    @Test
    fun usesManifestAppStartForTwoKilobyteBootAreaHexBounds() {
        val accepted = SignalSlingerFirmwareUpdate.parseIntelHexPages(
            intelHex(
                0x2000 to byteArrayOf(0x01, 0x02),
                0x2200 to byteArrayOf(0x03),
            ),
            appStartAddress = 0x2000,
        )

        assertEquals(listOf(0x2000, 0x2200), accepted.map { it.address })

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.parseIntelHexPages(
                intelHex(
                    0x1FFF to byteArrayOf(0x12),
                    0x2000 to byteArrayOf(0x34),
                ),
                appStartAddress = 0x2000,
            )
        }
        assertTrue(error.message.orEmpty().contains("0x00001FFF"))
    }

    @Test
    fun parsesExtendedSegmentAddressRecords() {
        val hex = listOf(
            record(0x4000, 0x00, byteArrayOf(0x01)),
            record(0x0000, 0x02, byteArrayOf(0x10, 0x00)),
            record(0x0000, 0x00, byteArrayOf(0x02)),
            ":00000001FF",
        ).joinToString("\n")

        val pages = SignalSlingerFirmwareUpdate.parseIntelHexPages(hex, appStartAddress = 0x4000)

        assertEquals(listOf(0x4000, 0x10000), pages.map { it.address })
        assertEquals(0x02, pages.last().bytes.first().toInt() and 0xFF)
    }

    @Test
    fun buildsPagePlanWithResetVectorPageWrittenLast() {
        val pages = SignalSlingerFirmwareUpdate.parseIntelHexPages(
            intelHex(
                0x4000 to byteArrayOf(0x01, 0x02),
                0x4200 to byteArrayOf(0x03),
                0x4400 to byteArrayOf(0x04),
            ),
            appStartAddress = 0x4000,
        )

        val plan = SignalSlingerFirmwareUpdate.buildUpdatePlan(pages, appStartAddress = 0x4000)

        assertEquals(listOf(0x4000, 0x4200, 0x4400), pages.map { it.address })
        assertEquals(
            listOf(
                SignalSlingerFirmwareOperationKind.ERASE to 0x4000,
                SignalSlingerFirmwareOperationKind.ERASE to 0x4200,
                SignalSlingerFirmwareOperationKind.WRITE to 0x4200,
                SignalSlingerFirmwareOperationKind.CRC to 0x4200,
                SignalSlingerFirmwareOperationKind.ERASE to 0x4400,
                SignalSlingerFirmwareOperationKind.WRITE to 0x4400,
                SignalSlingerFirmwareOperationKind.CRC to 0x4400,
                SignalSlingerFirmwareOperationKind.WRITE to 0x4000,
                SignalSlingerFirmwareOperationKind.CRC to 0x4000,
                SignalSlingerFirmwareOperationKind.RUN to null,
            ),
            plan.operations.map { it.kind to it.address },
        )
    }

    @Test
    fun buildsPagePlanWithTwoKilobyteResetVectorPageWrittenLast() {
        val pages = SignalSlingerFirmwareUpdate.parseIntelHexPages(
            intelHex(
                0x2000 to byteArrayOf(0x01, 0x02),
                0x2200 to byteArrayOf(0x03),
                0x2400 to byteArrayOf(0x04),
            ),
            appStartAddress = 0x2000,
        )

        val plan = SignalSlingerFirmwareUpdate.buildUpdatePlan(pages, appStartAddress = 0x2000)

        assertEquals(listOf(0x2000, 0x2200, 0x2400), pages.map { it.address })
        assertEquals(
            listOf(
                SignalSlingerFirmwareOperationKind.ERASE to 0x2000,
                SignalSlingerFirmwareOperationKind.ERASE to 0x2200,
                SignalSlingerFirmwareOperationKind.WRITE to 0x2200,
                SignalSlingerFirmwareOperationKind.CRC to 0x2200,
                SignalSlingerFirmwareOperationKind.ERASE to 0x2400,
                SignalSlingerFirmwareOperationKind.WRITE to 0x2400,
                SignalSlingerFirmwareOperationKind.CRC to 0x2400,
                SignalSlingerFirmwareOperationKind.WRITE to 0x2000,
                SignalSlingerFirmwareOperationKind.CRC to 0x2000,
                SignalSlingerFirmwareOperationKind.RUN to null,
            ),
            plan.operations.map { it.kind to it.address },
        )
    }

    @Test
    fun constructsCrcProtectedFramesWithLittleEndianFields() {
        assertEquals(
            "45 00 40 00 00 9E 3E",
            SignalSlingerFirmwareUpdate.eraseFrame(0x4000).toHex(),
        )
        assertEquals(
            "43 00 40 00 00 1B F3",
            SignalSlingerFirmwareUpdate.pageCrcFrame(0x4000).toHex(),
        )

        val write = SignalSlingerFirmwareUpdate.writeFrame(0x4000, ByteArray(512) { 0xFF.toByte() })
        assertEquals(519, write.size)
        assertEquals("57 00 40 00 00", write.take(5).toByteArray().toHex())
        assertEquals("FF FF FF FF AF F3", write.takeLast(6).toByteArray().toHex())
    }

    @Test
    fun constructsFramesForTwoKilobyteAppStart() {
        assertEquals("00 20 00 00", SignalSlingerFirmwareUpdate.eraseFrame(0x2000).sliceArray(1..4).toHex())
        assertEquals("00 20 00 00", SignalSlingerFirmwareUpdate.pageCrcFrame(0x2000).sliceArray(1..4).toHex())
        assertEquals(
            "57 00 20 00 00",
            SignalSlingerFirmwareUpdate.writeFrame(0x2000, ByteArray(512) { 0xFF.toByte() }).take(5).toByteArray().toHex(),
        )
    }

    @Test
    fun parsesOkCrcResponse() {
        val response = assertNotNull(SignalSlingerFirmwareUpdate.parsePageCrcResponse("OK crc 0x00004000 6995"))

        assertEquals(0x4000, response.address)
        assertEquals(0x6995, response.crc)
        assertEquals(null, SignalSlingerFirmwareUpdate.parsePageCrcResponse("ERR crc"))
    }

    @Test
    fun parsesAndValidatesBootloaderIdentity() {
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray()))
        val identity = assertNotNull(
            SignalSlingerFirmwareUpdate.parseIdentityLine(
                "SignalSlinger BL0.10 proto=1 app=0x4000 page=512 flash=131072 baud=115200 boot=32 cmds=U,R,?,E,W,C",
            ),
        )

        assertEquals("SignalSlinger", identity.product)
        assertEquals("BL0.10", identity.bootloaderVersion)
        assertEquals(1, identity.protocolVersion)
        assertEquals(0x4000, identity.appStartAddress)
        assertEquals(512, identity.pageBytes)
        assertEquals(131_072, identity.flashBytes)
        assertEquals(115_200, identity.baud)
        assertEquals(setOf('U', 'R', '?', 'E', 'W', 'C'), identity.commands)
        identity.validateForRelease(release)
    }

    @Test
    fun parsesAndValidatesBootloaderWritableRange() {
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(
            sampleManifest(
                updateBytes = sampleHex(appStartAddress = 0x2000).encodeToByteArray(),
                appStartAddress = 0x2000,
            ),
        )
        val identity = assertNotNull(
            SignalSlingerFirmwareUpdate.parseIdentityLine(
                "SignalSlinger BL0.12 proto=1 app=0x2000 page=512 flash=131072 baud=115200 boot=16 cmds=U,R,?,E,W,C write=0x2000-0x1FFFF",
            ),
        )

        assertEquals("BL0.12", identity.bootloaderVersion)
        assertEquals(0x2000, identity.appStartAddress)
        assertEquals(16, identity.bootSectionPages)
        assertEquals(0x2000, identity.writableStartAddress)
        assertEquals(0x1FFFF, identity.writableEndAddress)
        identity.validateForRelease(release)
        identity.validateWritableRangeForImage(setOf(0x2000, 0x2001, 0x1FFFF))
    }

    @Test
    fun rejectsUpdateImageOutsideBootloaderWritableRange() {
        val identity = assertNotNull(
            SignalSlingerFirmwareUpdate.parseIdentityLine(
                "SignalSlinger BL0.12 proto=1 app=0x2000 page=512 flash=131072 baud=115200 boot=16 cmds=U,R,?,E,W,C write=0x3000-0x1FFFF",
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            identity.validateWritableRangeForImage(setOf(0x2000, 0x2200))
        }
        assertTrue(error.message.orEmpty().contains("outside bootloader writable range"))
    }

    @Test
    fun requiresBootloaderWritableRangeBeforeWriting() {
        val identity = assertNotNull(
            SignalSlingerFirmwareUpdate.parseIdentityLine(
                "SignalSlinger BL0.11 proto=1 app=0x2000 page=512 flash=131072 baud=115200 boot=16 cmds=U,R,?,E,W,C",
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            identity.validateWritableRangeForImage(setOf(0x2000, 0x2200))
        }
        assertTrue(error.message.orEmpty().contains("did not report its writable flash range"))
    }

    @Test
    fun ignoresNormalAppBannerAsBootloaderIdentity() {
        assertEquals(
            null,
            SignalSlingerFirmwareUpdate.parseIdentityLine("SignalSlinger 80m Radio Orienteering Transmitter"),
        )
    }

    @Test
    fun crc16CcittFalseMatchesKnownVector() {
        assertEquals(0x29B1, SignalSlingerFirmwareUpdate.crc16CcittFalse("123456789".encodeToByteArray()))
    }

    @Test
    fun fallsBackToResetEntryWhenUpdDoesNotAcknowledge() {
        val hex = sampleHex()
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(hex.encodeToByteArray()))
        val transport = FakeUpdateTransport(hex)

        SignalSlingerFirmwareUpdate.performUpdate(
            transport = transport,
            release = release,
            hexText = hex,
        )

        assertEquals(listOf(9_600, 115_200, 115_200, 115_200, 115_200, 9_600, 115_200, 9_600), transport.connectedBauds)
        assertEquals(listOf("GO 0\r", "**\r", "VER\r", "INF\r", "UPD\r"), transport.asciiWrites.take(5))
        assertTrue("RST\n" in transport.asciiWrites)
        assertTrue("R" in transport.asciiWrites)
        assertEquals("INF\r", transport.asciiWrites.last())
        assertEquals(listOf('E', 'W', 'C'), transport.binaryWrites.map { it[0].toInt().toChar() })
    }

    @Test
    fun prefersOpenPortBaudSwitchAfterUpdAcknowledgement() {
        val hex = sampleHex()
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(hex.encodeToByteArray()))
        val transport = FakeUpdateTransport(
            hex = hex,
            acknowledgeUpd = true,
            supportOpenPortBaudSwitch = true,
        )

        SignalSlingerFirmwareUpdate.performUpdate(
            transport = transport,
            release = release,
            hexText = hex,
        )

        assertEquals(listOf(9_600, 9_600), transport.connectedBauds)
        assertEquals(listOf(115_200), transport.reconfiguredBauds)
        assertTrue("RST\n" !in transport.asciiWrites)
        assertTrue("R" in transport.asciiWrites)
        assertEquals(listOf('E', 'W', 'C'), transport.binaryWrites.map { it[0].toInt().toChar() })
    }

    @Test
    fun performsUpdateWithTwoKilobyteBootAreaRelease() {
        val hex = sampleHex(appStartAddress = 0x2000)
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(
            sampleManifest(
                updateBytes = hex.encodeToByteArray(),
                appStartAddress = 0x2000,
            ),
        )
        val transport = FakeUpdateTransport(
            hex = hex,
            appStartAddress = 0x2000,
            acknowledgeUpd = true,
            supportOpenPortBaudSwitch = true,
        )

        SignalSlingerFirmwareUpdate.performUpdate(
            transport = transport,
            release = release,
            hexText = hex,
        )

        assertEquals(0x2000, release.serialSlinger.appStartAddress)
        assertEquals('E', transport.binaryWrites.first()[0].toInt().toChar())
        assertEquals("00 20 00 00", transport.binaryWrites.first().sliceArray(1..4).toHex())
        assertEquals('C', transport.binaryWrites.last()[0].toInt().toChar())
        assertEquals("00 20 00 00", transport.binaryWrites.last().sliceArray(1..4).toHex())
        assertTrue("R" in transport.asciiWrites)
    }

    @Test
    fun rejectsManifestProductBeforeWriting() {
        val hex = sampleHex()
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(
            sampleManifest(
                updateBytes = hex.encodeToByteArray(),
                product = "OtherSlinger",
            ),
        )
        val transport = FakeUpdateTransport(hex)

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.performUpdate(
                transport = transport,
                release = release,
                hexText = hex,
            )
        }

        assertTrue(error.message.orEmpty().contains("product `OtherSlinger` is not supported"))
        assertEquals(emptyList(), transport.binaryWrites)
    }

    @Test
    fun rejectsHexStartAddressThatDoesNotMatchManifest() {
        val hex = intelHex(0x4200 to byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(hex.encodeToByteArray()))
        val transport = FakeUpdateTransport(hex)

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.performUpdate(
                transport = transport,
                release = release,
                hexText = hex,
            )
        }

        assertTrue(error.message.orEmpty().contains("release metadata says 0x00004000"))
        assertEquals(emptyList(), transport.binaryWrites)
    }

    @Test
    fun recoveryModeStillRequiresWritableRangeCompatibility() {
        val hex = sampleHex(appStartAddress = 0x2000)
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(
            sampleManifest(
                updateBytes = hex.encodeToByteArray(),
                appStartAddress = 0x2000,
            ),
        )
        val transport = FakeUpdateTransport(
            hex = hex,
            appStartAddress = 0x2000,
            writableStartAddress = 0x3000,
            startInUpdateMode = true,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.performUpdate(
                transport = transport,
                release = release,
                hexText = hex,
                recoverAlreadyWaiting = true,
            )
        }

        assertTrue(error.message.orEmpty().contains("outside bootloader writable range"))
        assertEquals(emptyList(), transport.binaryWrites)
    }

    @Test
    fun doesNotRetryUpdateModeAfterRunCommand() {
        val hex = sampleHex()
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(hex.encodeToByteArray()))
        val transport = FakeUpdateTransport(hex, respondToPostRunInfo = false)

        val failure = assertFailsWith<IllegalStateException> {
            SignalSlingerFirmwareUpdate.performUpdate(
                transport = transport,
                release = release,
                hexText = hex,
            )
        }

        assertTrue(failure.message.orEmpty().contains("could not confirm the updated firmware after restart"))
        assertEquals("R", transport.asciiWrites.last { it == "R" })
        assertEquals(0, transport.asciiWrites.dropWhile { it != "R" }.count { it == "U" })
        assertEquals(9_600, transport.connectedBauds.last())
    }

    private fun sampleManifest(
        updateBytes: ByteArray,
        appStartAddress: Int = 0x4000,
        product: String = "SignalSlinger",
    ): String {
        val startAddress = "0x" + appStartAddress.toString(16).uppercase()
        return """
            {
              "format": "signalslinger-release-info-v1",
              "product": "$product",
              "version": "2.0.0",
              "board": "HW-3.5",
              "generatedUtc": "2026-05-08T00:00:00Z",
              "gitCommit": "abcdef",
              "update": {
                "fileName": "SignalSlinger-Update-v2.0.0-HW-3.5.hex",
                "startAddress": "$startAddress",
                "bytesInImage": 4
              },
              "serialSlinger": {
                "appBaud": 9600,
                "updateBaud": 115200,
                "appInfoCommand": "INF",
                "appUpdateCommand": "UPD",
                "bootloaderEntryCommand": "U",
                "pageBytes": 512,
                "protocolVersion": 1,
                "bootloaderVersion": "BL0.11",
                "appStartAddress": "$startAddress",
                "flashBytes": 131072
              },
              "files": [
                {
                  "fileName": "SignalSlinger-Update-v2.0.0-HW-3.5.hex",
                  "kind": "update",
                  "purpose": "Update an existing SignalSlinger",
                  "sizeBytes": ${updateBytes.size},
                  "sha256": "${sha256ForSampleHex()}"
                }
              ]
            }
        """.trimIndent()
    }

    private fun sampleHex(appStartAddress: Int = 0x4000): String = intelHex(appStartAddress to byteArrayOf(0x01, 0x02, 0x03, 0x04))

    private fun sha256ForSampleHex(): String {
        return "1d9878e560beae019c8f407190e576b5c61c7ad9ab2a55a38ee5f1d97e1bfef6"
    }

    private fun sampleWorkshopManifest(): String {
        return """
            {
              "format": "signalslinger-release-info-v1",
              "product": "SignalSlinger",
              "version": "2.0.2",
              "board": "HW-3.5",
              "update": {
                "fileName": "SignalSlinger-Update-v2.0.2-HW-3.5.hex",
                "startAddress": "0x2000",
                "bytesInImage": 90226
              },
              "firstInstall": {
                "fileName": "SignalSlinger-First-Install-v2.0.2-HW-3.5.hex",
                "bytesInImage": 92904
              },
              "serialSlinger": {
                "appBaud": 9600,
                "updateBaud": 115200,
                "appInfoCommand": "INF",
                "appUpdateCommand": "UPD",
                "bootloaderEntryCommand": "U",
                "pageBytes": 512,
                "protocolVersion": 1,
                "bootloaderVersion": "BL0.12",
                "appStartAddress": "0x2000",
                "flashBytes": 131072
              },
              "workshopSetup": {
                "setupHelperFileName": "SignalSlinger-Setup-Helper-v2.0.2-HW-3.5-BL0.12.hex",
                "setupLauncherFileName": "Prepare-SignalSlinger-Updates-v2.0.2-HW-3.5.ps1",
                "provisioningScriptFileName": "provision-bootloader.ps1",
                "serialValidationScriptFileName": "test-bootloader-serial.ps1",
                "supportedProgrammers": ["atmelice", "pickit4", "snap", "powerdebugger", "edbg", "medbg", "nedbg"],
                "bootSectionPages": 16,
                "fuseBootSize": "0x10",
                "fuseCodeSize": "0x00"
              },
              "files": [
                {
                  "fileName": "SignalSlinger-Update-v2.0.2-HW-3.5.hex",
                  "kind": "update",
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000001"
                },
                {
                  "fileName": "SignalSlinger-First-Install-v2.0.2-HW-3.5.hex",
                  "kind": "first-install",
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000002"
                },
                {
                  "fileName": "SignalSlinger-Setup-Helper-v2.0.2-HW-3.5-BL0.12.hex",
                  "kind": "setup-helper",
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000003"
                },
                {
                  "fileName": "Prepare-SignalSlinger-Updates-v2.0.2-HW-3.5.ps1",
                  "kind": "workshop-setup-launcher",
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000004"
                },
                {
                  "fileName": "provision-bootloader.ps1",
                  "kind": "workshop-setup-tool",
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000005"
                },
                {
                  "fileName": "test-bootloader-serial.ps1",
                  "kind": "workshop-setup-tool",
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000006"
                }
              ]
            }
        """.trimIndent()
    }

    private fun intelHex(vararg chunks: Pair<Int, ByteArray>): String {
        val lines = mutableListOf<String>()
        var currentUpper: Int? = null
        chunks.sortedBy { it.first }.forEach { (address, bytes) ->
            val upper = address ushr 16
            if (upper != currentUpper) {
                currentUpper = upper
                lines += record(0x0000, 0x04, byteArrayOf(((upper ushr 8) and 0xFF).toByte(), (upper and 0xFF).toByte()))
            }
            lines += record(address and 0xFFFF, 0x00, bytes)
        }
        lines += ":00000001FF"
        return lines.joinToString("\n")
    }

    private fun record(
        offset: Int,
        type: Int,
        data: ByteArray,
    ): String {
        val values = mutableListOf(data.size, (offset ushr 8) and 0xFF, offset and 0xFF, type)
        values += data.map { it.toInt() and 0xFF }
        val checksum = ((values.sum().inv() + 1) and 0xFF)
        return ":" + (values + checksum).joinToString("") { it.toString(16).uppercase().padStart(2, '0') }
    }

    private fun ByteArray.toHex(): String {
        return joinToString(" ") { byte -> (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
    }

    private class FakeUpdateTransport(
        hex: String,
        private val appStartAddress: Int = 0x4000,
        private val writableStartAddress: Int = appStartAddress,
        private val writableEndAddress: Int = 0x1FFFF,
        private val respondToPostRunInfo: Boolean = true,
        private val acknowledgeUpd: Boolean = false,
        private val supportOpenPortBaudSwitch: Boolean = false,
        private val startInUpdateMode: Boolean = false,
    ) : SignalSlingerFirmwareUpdateTransport {
        val connectedBauds = mutableListOf<Int>()
        val reconfiguredBauds = mutableListOf<Int>()
        val asciiWrites = mutableListOf<String>()
        val binaryWrites = mutableListOf<ByteArray>()
        private val pages by lazy { SignalSlingerFirmwareUpdate.parseIntelHexPages(hex, appStartAddress = appStartAddress) }
        private var pendingLines = emptyList<String>()
        private var resetSent = false
        private var updateMode = startInUpdateMode
        private var runSent = false

        override fun connect(baudRate: Int) {
            connectedBauds += baudRate
        }

        override fun reconfigureBaudRate(baudRate: Int): Boolean {
            if (!supportOpenPortBaudSwitch) {
                return false
            }
            reconfiguredBauds += baudRate
            updateMode = true
            return true
        }

        override fun disconnect() {
        }

        override fun writeAscii(text: String) {
            asciiWrites += text
            pendingLines =
                when (text) {
                    "INF\r" ->
                        if (runSent && !respondToPostRunInfo) {
                            emptyList()
                        } else {
                            listOf(
                                "* INF product=SignalSlinger update=UPD",
                                "* INF sw=2.0.0 hw=3.5 app=${appStartAddress.toString(16).uppercase().padStart(4, '0').let { "0x$it" }} baud=115200",
                            )
                        }
                    "UPD\r" ->
                        if (acknowledgeUpd) {
                            listOf("* Bootloader update mode")
                        } else {
                            emptyList()
                        }
                    "RST\n" -> {
                        resetSent = true
                        emptyList()
                    }
                    "U" -> if (resetSent || updateMode) listOf("BOOT") else emptyList()
                    "?" ->
                        if (resetSent || updateMode) {
                            listOf("SignalSlinger BL0.12 proto=1 app=${appStartAddress.toString(16).uppercase().padStart(4, '0').let { "0x$it" }} page=512 flash=131072 baud=115200 boot=16 cmds=U,R,?,E,W,C write=${writableStartAddress.toString(16).uppercase().padStart(4, '0').let { "0x$it" }}-${writableEndAddress.toString(16).uppercase().padStart(5, '0').let { "0x$it" }}")
                        } else {
                            emptyList()
                        }
                    "R" -> {
                        runSent = true
                        emptyList()
                    }
                    else -> emptyList()
                }
        }

        override fun writeBytes(bytes: ByteArray) {
            binaryWrites += bytes
            pendingLines =
                when (bytes[0].toInt().toChar()) {
                    'E' -> listOf("OK erase")
                    'W' -> listOf("OK write")
                    'C' -> {
                        val address = (bytes[1].toInt() and 0xFF) or
                            ((bytes[2].toInt() and 0xFF) shl 8) or
                            ((bytes[3].toInt() and 0xFF) shl 16) or
                            ((bytes[4].toInt() and 0xFF) shl 24)
                        val page = pages.first { it.address == address }
                        val crc = SignalSlingerFirmwareUpdate.crc16CcittFalse(page.bytes)
                        listOf("OK crc 0x${address.toString(16).uppercase().padStart(8, '0')} ${crc.toString(16).uppercase().padStart(4, '0')}")
                    }
                    else -> emptyList()
                }
        }

        override fun readLines(timeoutMs: Long): List<String> {
            val lines = pendingLines
            pendingLines = emptyList()
            return lines
        }
    }
}
