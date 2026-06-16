package com.openardf.serialslinger.protocol

import com.openardf.serialslinger.platform.platformCurrentTimeMillis
import com.openardf.serialslinger.platform.platformSleep

private const val ArduconProduct = "Arducon"
private const val ArduconBoard = "ATmega328P"
private const val ArduconFormat = "arducon-release-info-v1"
private const val StkOk = 0x10
private const val StkInSync = 0x14
private const val StkCrcEop = 0x20
private const val OptibootWatchdogExitSettleMs = 1_500L
private const val ArduconRestartConfirmationFailureMessage =
    "Arducon update was sent, but the updated Arducon firmware could not be confirmed after restart."
private const val ArduconRestartConfirmationAttempts = 3
private const val ArduconBootloaderIncompatibleVersion = "2.1.0"
private const val ArduconStopTransmissionsCommand = "SYN 0"

class ArduconRestartHandoffException(message: String) : IllegalStateException(message)

data class ArduconReleaseInfo(
    val format: String,
    val product: String,
    val version: String,
    val board: String,
    val update: ArduconUpdateImage,
    val bootloader: ArduconBootloaderImage,
    val firstInstall: ArduconFirstInstallImage?,
    val workshopSetup: ArduconWorkshopSetup?,
    val firmwareUpdate: ArduconUpdateSettings,
    val files: List<ArduconReleaseFile>,
) {
    fun updateFile(): ArduconReleaseFile {
        return files.firstOrNull { it.kind == "update" && it.fileName == update.fileName }
            ?: error("Arducon release manifest does not list update file `${update.fileName}`.")
    }

    fun bootloaderFile(): ArduconReleaseFile {
        return files.firstOrNull { it.kind == "bootloader" && it.fileName == bootloader.fileName }
            ?: error("Arducon release manifest does not list bootloader file `${bootloader.fileName}`.")
    }

    fun firstInstallFile(): ArduconReleaseFile {
        val fileName = firstInstall?.fileName ?: error("Arducon release manifest does not include first-install metadata.")
        return files.firstOrNull { it.kind == "first-install" && it.fileName == fileName }
            ?: error("Arducon release manifest does not list first-install file `$fileName`.")
    }

    fun setupHelperFile(): ArduconReleaseFile {
        val fileName = workshopSetup?.setupHelperFileName ?: error("Arducon release manifest does not include workshop setup metadata.")
        return files.firstOrNull { it.kind == "setup-helper" && it.fileName == fileName }
            ?: error("Arducon release manifest does not list setup-helper file `$fileName`.")
    }

    fun setupLauncherFile(): ArduconReleaseFile {
        val fileName = workshopSetup?.setupLauncherFileName ?: error("Arducon release manifest does not include workshop setup launcher metadata.")
        return files.firstOrNull { it.kind == "workshop-setup-launcher" && it.fileName == fileName }
            ?: error("Arducon release manifest does not list workshop setup launcher `$fileName`.")
    }

    fun workshopSetupToolFiles(): List<ArduconReleaseFile> {
        return files.filter { it.kind == "workshop-setup-tool" }
    }
}

data class ArduconUpdateImage(
    val fileName: String,
    val startAddress: Int,
    val bytesInImage: Int,
)

data class ArduconBootloaderImage(
    val fileName: String,
    val sourceArchiveFileName: String,
    val protocol: String,
    val baud: Int,
    val startAddress: Int,
    val endAddress: Int,
    val bytesInImage: Int,
)

data class ArduconFirstInstallImage(
    val fileName: String,
    val bytesInImage: Int,
)

data class ArduconWorkshopSetup(
    val setupHelperFileName: String?,
    val setupLauncherFileName: String?,
    val provisioningScriptFileName: String?,
    val serialValidationScriptFileName: String?,
    val supportedProgrammers: List<String>,
    val bootSectionPages: Int?,
    val fuseBootSize: String?,
    val fuseCodeSize: String?,
    val highFuseTarget: String?,
    val highFuseTargetPreserveEeprom: String?,
    val extendedFuseBodLevelTarget: String?,
    val extendedFuseBodLevelDescription: String?,
)

data class ArduconUpdateSettings(
    val appBaud: Int,
    val updateBaud: Int,
    val appInfoCommand: String,
    val appUpdateCommand: String,
    val pageBytes: Int,
    val bootloaderProtocol: String,
    val appStartAddress: Int,
    val flashBytes: Int,
    val appLimitAddress: Int,
)

data class ArduconReleaseFile(
    val fileName: String,
    val kind: String,
    val purpose: String?,
    val sizeBytes: Int?,
    val sha256: String,
)

object ArduconFirmwareUpdate {
    fun parseReleaseInfo(jsonText: String): ArduconReleaseInfo {
        val root = JsonValue.parse(jsonText.trimStart('\uFEFF')).asObject()
        val update = root.requiredObject("update")
        val bootloader = root.requiredObject("bootloader")
        val firstInstall = root.optionalObject("firstInstall")
        val firmwareUpdate = root.requiredObject("firmwareUpdate")
        val workshopSetup = root.optionalObject("workshopSetup")
        val files = root.requiredArray("files").map { item ->
            val file = item.asObject()
            ArduconReleaseFile(
                fileName = file.requiredString("fileName"),
                kind = file.requiredString("kind"),
                purpose = file.optionalString("purpose"),
                sizeBytes = file.optionalInt("sizeBytes"),
                sha256 = file.requiredString("sha256").lowercase(),
            )
        }
        return ArduconReleaseInfo(
            format = root.requiredString("format"),
            product = root.requiredString("product"),
            version = root.requiredString("version"),
            board = root.requiredString("board"),
            update = ArduconUpdateImage(
                fileName = update.requiredString("fileName"),
                startAddress = parseIntLiteral(update.requiredString("startAddress")),
                bytesInImage = update.requiredInt("bytesInImage"),
            ),
            bootloader = ArduconBootloaderImage(
                fileName = bootloader.requiredString("fileName"),
                sourceArchiveFileName = bootloader.requiredString("sourceArchiveFileName"),
                protocol = bootloader.requiredString("protocol"),
                baud = bootloader.requiredInt("baud"),
                startAddress = parseIntLiteral(bootloader.requiredString("startAddress")),
                endAddress = parseIntLiteral(bootloader.requiredString("endAddress")),
                bytesInImage = bootloader.requiredInt("bytesInImage"),
            ),
            firstInstall = firstInstall?.let {
                ArduconFirstInstallImage(
                    fileName = it.requiredString("fileName"),
                    bytesInImage = it.requiredInt("bytesInImage"),
                )
            },
            workshopSetup = workshopSetup?.let {
                ArduconWorkshopSetup(
                    setupHelperFileName = it.optionalString("setupHelperFileName"),
                    setupLauncherFileName = it.optionalString("setupLauncherFileName"),
                    provisioningScriptFileName = it.optionalString("provisioningScriptFileName"),
                    serialValidationScriptFileName = it.optionalString("serialValidationScriptFileName"),
                    supportedProgrammers = it.optionalArray("supportedProgrammers")?.map { item -> item.asString() }.orEmpty(),
                    bootSectionPages = it.optionalInt("bootSectionPages"),
                    fuseBootSize = it.optionalString("fuseBootSize"),
                    fuseCodeSize = it.optionalString("fuseCodeSize"),
                    highFuseTarget = it.optionalString("highFuseTarget"),
                    highFuseTargetPreserveEeprom = it.optionalString("highFuseTargetPreserveEeprom"),
                    extendedFuseBodLevelTarget = it.optionalString("extendedFuseBodLevelTarget"),
                    extendedFuseBodLevelDescription = it.optionalString("extendedFuseBodLevelDescription"),
                )
            },
            firmwareUpdate = ArduconUpdateSettings(
                appBaud = firmwareUpdate.requiredInt("appBaud"),
                updateBaud = firmwareUpdate.requiredInt("updateBaud"),
                appInfoCommand = firmwareUpdate.requiredString("appInfoCommand"),
                appUpdateCommand = firmwareUpdate.requiredString("appUpdateCommand"),
                pageBytes = firmwareUpdate.requiredInt("pageBytes"),
                bootloaderProtocol = firmwareUpdate.requiredString("bootloaderProtocol"),
                appStartAddress = parseIntLiteral(firmwareUpdate.requiredString("appStartAddress")),
                flashBytes = firmwareUpdate.requiredInt("flashBytes"),
                appLimitAddress = parseIntLiteral(firmwareUpdate.requiredString("appLimitAddress")),
            ),
            files = files,
        )
    }

    fun verifyReleaseFileHash(
        releaseFile: ArduconReleaseFile,
        bytes: ByteArray,
    ) {
        releaseFile.sizeBytes?.let { expectedSize ->
            require(bytes.size == expectedSize) {
                "File `${releaseFile.fileName}` size mismatch: expected $expectedSize bytes, got ${bytes.size} bytes."
            }
        }
        val expected = releaseFile.sha256.lowercase()
        val actual = Sha256.digestHex(bytes)
        require(actual == expected) {
            "File `${releaseFile.fileName}` hash mismatch: expected $expected, got $actual."
        }
    }

    fun validateReleaseManifest(release: ArduconReleaseInfo) {
        require(release.format == ArduconFormat) {
            "Unsupported Arducon release manifest format `${release.format}`."
        }
        require(release.product == ArduconProduct) {
            "Update package product `${release.product}` is not supported."
        }
        require(release.board == ArduconBoard) {
            "Update package board `${release.board}` is not supported."
        }
        require(release.update.startAddress == 0x0000 && release.firmwareUpdate.appStartAddress == 0x0000) {
            "Arducon update packages must start at 0x0000."
        }
        require(release.firmwareUpdate.appLimitAddress == 0x7E00) {
            "Arducon update package app limit ${release.firmwareUpdate.appLimitAddress.toHex32()} is not supported."
        }
        require(release.firmwareUpdate.flashBytes == 32_768) {
            "Arducon update package flash size ${release.firmwareUpdate.flashBytes} is not supported."
        }
        require(release.firmwareUpdate.pageBytes == 128) {
            "Arducon update package page size ${release.firmwareUpdate.pageBytes} is not supported."
        }
        require(release.firmwareUpdate.appBaud == 57_600 && release.firmwareUpdate.updateBaud == 115_200) {
            "Arducon update package baud settings are not supported."
        }
        require(release.firmwareUpdate.bootloaderProtocol.equals("stk500v1", ignoreCase = true)) {
            "Arducon update package protocol `${release.firmwareUpdate.bootloaderProtocol}` is not supported."
        }
        require(release.bootloader.protocol.equals("stk500v1", ignoreCase = true)) {
            "Arducon bootloader protocol `${release.bootloader.protocol}` is not supported."
        }
        require(release.bootloader.startAddress == 0x7E00 && release.bootloader.endAddress == 0x7FFF) {
            "Arducon bootloader address range is not supported."
        }
        require(release.update.fileName.contains(ArduconProduct) && release.update.fileName.contains(ArduconBoard)) {
            "Update file `${release.update.fileName}` does not identify Arducon ${ArduconBoard}."
        }
        require(release.updateFile().sha256.matches(Regex("[0-9a-f]{64}"))) {
            "Update file `${release.update.fileName}` has an invalid SHA-256 value in the release manifest."
        }
    }

    fun performUpdate(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: ArduconReleaseInfo,
        hexText: String,
        recoverAlreadyWaiting: Boolean = false,
        confirmedAppInfo: SignalSlingerAppInfo? = null,
        stkCommandPaceMs: Long = 0,
        verifyReadback: Boolean = true,
        progress: (SignalSlingerFirmwareUpdateProgress) -> Unit = {},
    ) {
        progress(SignalSlingerFirmwareUpdateProgress("Preparing update", 0, 0))
        validateReleaseManifest(release)
        val image = parseIntelHexImage(hexText, release.firmwareUpdate.appStartAddress, release.firmwareUpdate.appLimitAddress)
        require(image.keys.minOrNull() == release.update.startAddress) {
            "Update image starts at ${image.keys.minOrNull()?.toHex32() ?: "unknown"} but release metadata says ${release.update.startAddress.toHex32()}."
        }
        require(image.size == release.update.bytesInImage) {
            "Update image byte count mismatch: manifest says ${release.update.bytesInImage}, HEX contains ${image.size}."
        }
        val pages = pagesFromImage(image, release.firmwareUpdate.pageBytes)

        enterUpdateMode(transport, release, recoverAlreadyWaiting, confirmedAppInfo, progress)
        stkCommand(transport, byteArrayOf(0x50), 0, "enter programming mode", stkCommandPaceMs)

        pages.forEachIndexed { index, page ->
            progress(SignalSlingerFirmwareUpdateProgress("Sending update", index, pages.size, page.address.toHex32()))
            loadAddress(transport, page.address, stkCommandPaceMs)
            stkCommand(
                transport = transport,
                command = byteArrayOf(0x64, 0x00, page.bytes.size.toByte(), 'F'.code.toByte()) + page.bytes,
                responseBytes = 0,
                description = "program page ${page.address.toHex32()}",
                paceMs = stkCommandPaceMs,
            )
        }

        if (verifyReadback) {
            pages.forEachIndexed { index, page ->
                progress(SignalSlingerFirmwareUpdateProgress("Verifying update", index, pages.size, page.address.toHex32()))
                loadAddress(transport, page.address, stkCommandPaceMs)
                val readback = stkCommand(
                    transport = transport,
                    command = byteArrayOf(0x74, 0x00, page.bytes.size.toByte(), 'F'.code.toByte()),
                    responseBytes = page.bytes.size,
                    description = "read page ${page.address.toHex32()}",
                    paceMs = stkCommandPaceMs,
                )
                require(readback.contentEquals(page.bytes)) {
                    "Arducon verification failed at ${page.address.toHex32()}."
                }
            }
        } else {
            progress(SignalSlingerFirmwareUpdateProgress("Verifying update", pages.size, pages.size, "Skipping bootloader readback; confirming Arducon app after restart."))
        }

        progress(SignalSlingerFirmwareUpdateProgress("Restarting Arducon", pages.size, pages.size))
        stkCommand(transport, byteArrayOf(0x51), 0, "leave programming mode", stkCommandPaceMs)
        val resetPulsed = transport.pulseTargetReset()
        if (resetPulsed) {
            progress(SignalSlingerFirmwareUpdateProgress("Restarting Arducon", pages.size, pages.size, "Pulsed Arducon reset after update."))
        } else {
            progress(SignalSlingerFirmwareUpdateProgress("Restarting Arducon", pages.size, pages.size, "Waiting for Arducon app after leaving bootloader."))
            platformSleep(OptibootWatchdogExitSettleMs)
        }
        confirmUpdatedApp(transport, release, progress)
    }

    private fun enterUpdateMode(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: ArduconReleaseInfo,
        recoverAlreadyWaiting: Boolean,
        confirmedAppInfo: SignalSlingerAppInfo?,
        progress: (SignalSlingerFirmwareUpdateProgress) -> Unit,
    ) {
        if (!recoverAlreadyWaiting) {
            transport.connect(release.firmwareUpdate.appBaud)
            val appInfo = readAppInfo(transport, release)
                ?: confirmedAppInfo?.takeIf { it.hasRequiredArduconUpdateIdentity() }
                ?: error("Could not confirm Arducon firmware update support.")
            validateAppInfoForRelease(appInfo, release, requireVersion = false)
            transport.writeAscii("${release.firmwareUpdate.appUpdateCommand}\r")
            transport.readLines(300)
            progress(SignalSlingerFirmwareUpdateProgress("Preparing update", 0, 0, "Switching Arducon update connection to ${release.firmwareUpdate.updateBaud} baud"))
            require(transport.reconfigureBaudRate(release.firmwareUpdate.updateBaud)) {
                "Could not switch the open Arducon serial connection to ${release.firmwareUpdate.updateBaud} baud."
            }
        } else {
            progress(SignalSlingerFirmwareUpdateProgress("Preparing update", 0, 0, "Waiting for Arducon update mode"))
            transport.connect(release.firmwareUpdate.updateBaud)
            if (transport.pulseTargetReset()) {
                progress(SignalSlingerFirmwareUpdateProgress("Preparing update", 0, 0, "Pulsed Arducon reset for bootloader recovery."))
            }
        }
        repeat(10) { attempt ->
            if (trySync(transport)) {
                return
            }
            if (attempt == 0) {
                progress(SignalSlingerFirmwareUpdateProgress("Preparing update", 0, 0, "Retrying Arducon bootloader sync."))
            }
            platformSleep(150)
        }
        if (appStillRespondsAfterBootloaderEntry(transport, release)) {
            error(
                "Arducon accepted the update command, but the app is still running at ${release.firmwareUpdate.appBaud} baud. " +
                    "The connected device did not transfer control to an STK500v1/Optiboot bootloader.",
            )
        }
        error("Arducon did not enter update mode.")
    }

    private fun appStillRespondsAfterBootloaderEntry(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: ArduconReleaseInfo,
    ): Boolean {
        return runCatching {
            transport.disconnect()
            platformSleep(200)
            transport.connect(release.firmwareUpdate.appBaud)
            readAppInfo(transport, release)?.product == ArduconProduct
        }.getOrDefault(false)
    }

    private fun confirmUpdatedApp(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: ArduconReleaseInfo,
        progress: (SignalSlingerFirmwareUpdateProgress) -> Unit,
    ) {
        var lastFailure: Throwable? = null
        repeat(ArduconRestartConfirmationAttempts) { attempt ->
            progress(
                SignalSlingerFirmwareUpdateProgress(
                    "Restarting Arducon",
                    attempt,
                    ArduconRestartConfirmationAttempts,
                    "Checking Arducon app after restart (${attempt + 1}/$ArduconRestartConfirmationAttempts)",
                ),
            )
            try {
                transport.disconnect()
            } catch (_: Throwable) {
            }
            platformSleep(1_000)
            try {
                transport.connect(release.firmwareUpdate.appBaud)
                val appInfo = readAppInfo(
                    transport = transport,
                    release = release,
                    attempts = 3,
                    drainTimeoutMs = 300,
                    responseTimeoutMs = 1_200,
                    sendStopCommand = false,
                )
                    ?: error(ArduconRestartConfirmationFailureMessage)
                validateAppInfoForRelease(appInfo, release, requireVersion = true)
                return
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            progress(
                SignalSlingerFirmwareUpdateProgress(
                    "Restarting Arducon",
                    attempt + 1,
                    ArduconRestartConfirmationAttempts,
                    "Checking whether Arducon is still in bootloader mode.",
                ),
            )
            if (bootloaderStillRespondsAfterLeaveProgramming(transport, release)) {
                throw ArduconRestartHandoffException(
                    "Arducon update was sent and verified, but Arducon did not restart into normal operation. " +
                        "Manually restart the Arducon by cycling power.",
                )
            }
        }
        throw lastFailure ?: IllegalStateException(ArduconRestartConfirmationFailureMessage)
    }

    private fun bootloaderStillRespondsAfterLeaveProgramming(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: ArduconReleaseInfo,
    ): Boolean {
        return runCatching {
            transport.disconnect()
            platformSleep(200)
            transport.connect(release.firmwareUpdate.updateBaud)
            trySync(transport)
        }.getOrDefault(false)
    }

    private fun readAppInfo(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: ArduconReleaseInfo,
        attempts: Int = 3,
        drainTimeoutMs: Long = 300,
        responseTimeoutMs: Long = 1_200,
        sendStopCommand: Boolean = true,
    ): SignalSlingerAppInfo? {
        val allLines = mutableListOf<String>()
        repeat(attempts.coerceAtLeast(1)) {
            transport.readLines(drainTimeoutMs)
            if (sendStopCommand) {
                transport.writeAscii("$ArduconStopTransmissionsCommand\r")
                transport.readLines(drainTimeoutMs)
            }
            transport.writeAscii("${release.firmwareUpdate.appInfoCommand}\r")
            allLines += transport.readLines(responseTimeoutMs)
            SignalSlingerFirmwareUpdate.parseAppInfo(allLines)
                ?.takeIf { it.hasRequiredArduconUpdateIdentity() }
                ?.let { return it }
        }
        return null
    }

    private fun SignalSlingerAppInfo.hasRequiredArduconUpdateIdentity(): Boolean {
        return product != null &&
            softwareVersion != null &&
            hardwareBuild != null &&
            appStartAddress != null &&
            updateBaud != null &&
            appUpdateCommand != null &&
            bootloaderProtocol != null
    }

    private fun validateAppInfoForRelease(
        info: SignalSlingerAppInfo,
        release: ArduconReleaseInfo,
        requireVersion: Boolean,
    ) {
        require(info.product == ArduconProduct) {
            "Connected device product `${info.product.orEmpty()}` does not match package product `${release.product}`."
        }
        require(info.hardwareBuild == "ATmega328P-16" || info.hardwareBuild == release.board) {
            "Connected firmware build ${info.hardwareBuild.orEmpty().ifBlank { "unknown" }} does not match package board `${release.board}`."
        }
        require(info.appStartAddress == release.firmwareUpdate.appStartAddress) {
            "Connected app start ${info.appStartAddress?.toHex32() ?: "unknown"} does not match package app ${release.firmwareUpdate.appStartAddress.toHex32()}."
        }
        require(info.appBaud == null || info.appBaud == release.firmwareUpdate.appBaud) {
            "Connected app baud ${info.appBaud} does not match package app baud ${release.firmwareUpdate.appBaud}."
        }
        require(info.updateBaud == release.firmwareUpdate.updateBaud) {
            "Connected update baud ${info.updateBaud ?: "unknown"} does not match package baud ${release.firmwareUpdate.updateBaud}."
        }
        require(info.appUpdateCommand == release.firmwareUpdate.appUpdateCommand) {
            "Connected update command `${info.appUpdateCommand.orEmpty()}` does not match package update command `${release.firmwareUpdate.appUpdateCommand}`."
        }
        require(info.bootloaderProtocol.equals(release.firmwareUpdate.bootloaderProtocol, ignoreCase = true)) {
            "Connected bootloader protocol `${info.bootloaderProtocol.orEmpty()}` does not match package protocol `${release.firmwareUpdate.bootloaderProtocol}`."
        }
        require(!wouldDowngradeAcrossArduconBootloaderBoundary(info.softwareVersion, release.version)) {
            "Arducon firmware ${info.softwareVersion.orEmpty().ifBlank { "unknown" }} cannot be downgraded to ${release.version}. " +
                "Arducon 2.1.0 and newer use a bootloader that is not compatible with earlier firmware."
        }
        if (requireVersion) {
            require(info.softwareVersion == release.version) {
                "Updated Arducon reported firmware ${info.softwareVersion.orEmpty().ifBlank { "unknown" }} instead of ${release.version}."
            }
        }
    }

    private fun wouldDowngradeAcrossArduconBootloaderBoundary(
        currentVersion: String?,
        targetVersion: String,
    ): Boolean {
        return SignalSlingerFirmwareUpdate.compareVersionStrings(currentVersion, ArduconBootloaderIncompatibleVersion) >= 0 &&
            SignalSlingerFirmwareUpdate.compareVersionStrings(targetVersion, ArduconBootloaderIncompatibleVersion) < 0
    }

    private fun trySync(transport: SignalSlingerFirmwareUpdateTransport): Boolean =
        runCatching {
            stkCommand(transport, byteArrayOf(0x30), 0, "sync")
            true
        }.getOrDefault(false)

    private fun loadAddress(
        transport: SignalSlingerFirmwareUpdateTransport,
        byteAddress: Int,
        paceMs: Long = 0,
    ) {
        val wordAddress = byteAddress / 2
        stkCommand(
            transport = transport,
            command = byteArrayOf(0x55, (wordAddress and 0xFF).toByte(), ((wordAddress ushr 8) and 0xFF).toByte()),
            responseBytes = 0,
            description = "load address ${byteAddress.toHex32()}",
            paceMs = paceMs,
        )
    }

    private fun stkCommand(
        transport: SignalSlingerFirmwareUpdateTransport,
        command: ByteArray,
        responseBytes: Int,
        description: String,
        paceMs: Long = 0,
    ): ByteArray {
        transport.writeBytes(command + StkCrcEop.toByte())
        val response = readStkResponse(transport, responseBytes, 1_500)
        require(response != null) {
            "Arducon bootloader did not respond to $description."
        }
        if (paceMs > 0) {
            platformSleep(paceMs)
        }
        return response
    }

    private fun readStkResponse(
        transport: SignalSlingerFirmwareUpdateTransport,
        responseBytes: Int,
        timeoutMs: Long,
    ): ByteArray? {
        val deadline = platformCurrentTimeMillis() + timeoutMs
        val bytes = mutableListOf<Byte>()
        while (platformCurrentTimeMillis() <= deadline) {
            bytes += transport.readBytes(100).toList()
            val inSyncIndex = bytes.indexOfFirst { (it.toInt() and 0xFF) == StkInSync }
            if (inSyncIndex >= 0 && bytes.size >= inSyncIndex + 2 + responseBytes) {
                val okIndex = inSyncIndex + 1 + responseBytes
                if ((bytes[okIndex].toInt() and 0xFF) == StkOk) {
                    return bytes.subList(inSyncIndex + 1, okIndex).toByteArray()
                }
            }
        }
        return null
    }

    private fun parseIntelHexImage(
        hexText: String,
        appStartAddress: Int,
        appLimitAddress: Int,
    ): Map<Int, Byte> {
        var upperAddress = 0
        val image = mutableMapOf<Int, Byte>()
        hexText.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            require(line.startsWith(":")) { "Intel HEX line ${index + 1} does not start with ':'." }
            val bytes = line.drop(1).chunked(2).map { it.toInt(16) }
            require(bytes.size >= 5) { "Intel HEX line ${index + 1} is too short." }
            val byteCount = bytes[0]
            require(bytes.size == byteCount + 5) { "Intel HEX line ${index + 1} length does not match byte count." }
            require((bytes.sum() and 0xFF) == 0) { "Intel HEX line ${index + 1} checksum is invalid." }
            val offset = (bytes[1] shl 8) or bytes[2]
            val type = bytes[3]
            val data = bytes.drop(4).take(byteCount)
            when (type) {
                0x00 -> {
                    val baseAddress = upperAddress + offset
                    data.forEachIndexed { dataIndex, value ->
                        val address = baseAddress + dataIndex
                        require(address in appStartAddress until appLimitAddress) {
                            "Update image contains data outside app flash at ${address.toHex32()}."
                        }
                        image[address] = value.toByte()
                    }
                }
                0x01 -> return image
                0x02 -> {
                    require(byteCount == 2) { "Intel HEX line ${index + 1} has invalid extended segment address length." }
                    upperAddress = (((data[0] shl 8) or data[1]) shl 4)
                }
                0x04 -> {
                    require(byteCount == 2) { "Intel HEX line ${index + 1} has invalid extended linear address length." }
                    upperAddress = (((data[0] shl 8) or data[1]) shl 16)
                }
                else -> Unit
            }
        }
        return image
    }

    private fun pagesFromImage(
        image: Map<Int, Byte>,
        pageBytes: Int,
    ): List<SignalSlingerFirmwarePage> {
        require(image.keys.any { it in 0 until pageBytes }) {
            "Update image does not contain the reset-vector page at ${0.toHex32()}."
        }
        return image.keys
            .map { address -> address - (address % pageBytes) }
            .distinct()
            .sorted()
            .map { pageAddress ->
                val page = ByteArray(pageBytes) { 0xFF.toByte() }
                for (offset in 0 until pageBytes) {
                    page[offset] = image[pageAddress + offset] ?: 0xFF.toByte()
                }
                SignalSlingerFirmwarePage(address = pageAddress, bytes = page)
            }
    }
}

private fun Int.toHex32(): String = "0x" + toString(16).uppercase().padStart(8, '0')

private fun parseIntLiteral(text: String): Int {
    val normalized = text.trim()
    return if (normalized.startsWith("0x", ignoreCase = true)) {
        normalized.drop(2).toInt(16)
    } else {
        normalized.toInt()
    }
}

private fun Map<String, JsonValue>.requiredString(key: String): String = required(key).asString()

private fun Map<String, JsonValue>.optionalString(key: String): String? = this[key]?.asString()

private fun Map<String, JsonValue>.requiredInt(key: String): Int = required(key).asInt()

private fun Map<String, JsonValue>.optionalInt(key: String): Int? = this[key]?.asInt()

private fun Map<String, JsonValue>.requiredObject(key: String): Map<String, JsonValue> = required(key).asObject()

private fun Map<String, JsonValue>.optionalObject(key: String): Map<String, JsonValue>? = this[key]?.asObject()

private fun Map<String, JsonValue>.requiredArray(key: String): List<JsonValue> = required(key).asArray()

private fun Map<String, JsonValue>.optionalArray(key: String): List<JsonValue>? = this[key]?.asArray()

private fun Map<String, JsonValue>.required(key: String): JsonValue = this[key] ?: error("Release manifest is missing `$key`.")
