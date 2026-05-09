package com.openardf.serialslinger.protocol

private const val DefaultFlashBytes = 0x20000
private const val DefaultPageBytes = 512
private const val MaxTransferAttempts = 2
private const val UpdateModeReconnectSettleMs = 500L

private fun Int.toHex16(): String = "0x" + toString(16).uppercase().padStart(4, '0')

private fun Int.toHex32(): String = "0x" + toString(16).uppercase().padStart(8, '0')

data class SignalSlingerReleaseInfo(
    val format: String,
    val product: String,
    val version: String,
    val board: String,
    val update: SignalSlingerUpdateImage,
    val firstInstall: SignalSlingerFirstInstallImage?,
    val serialSlinger: SignalSlingerUpdateSettings,
    val workshopSetup: SignalSlingerWorkshopSetup?,
    val files: List<SignalSlingerReleaseFile>,
) {
    fun updateFile(): SignalSlingerReleaseFile {
        return files.firstOrNull { it.kind == "update" && it.fileName == update.fileName }
            ?: error("Release manifest does not list update file `${update.fileName}`.")
    }

    fun firstInstallFile(): SignalSlingerReleaseFile {
        val fileName = firstInstall?.fileName ?: error("Release manifest does not include workshop first-install metadata.")
        return files.firstOrNull { it.kind == "first-install" && it.fileName == fileName }
            ?: error("Release manifest does not list first-install file `$fileName`.")
    }

    fun setupHelperFile(): SignalSlingerReleaseFile {
        val fileName = workshopSetup?.setupHelperFileName ?: error("Release manifest does not include workshop setup metadata.")
        return files.firstOrNull { it.kind == "setup-helper" && it.fileName == fileName }
            ?: error("Release manifest does not list setup-helper file `$fileName`.")
    }

    fun setupLauncherFile(): SignalSlingerReleaseFile {
        val fileName = workshopSetup?.setupLauncherFileName ?: error("Release manifest does not include workshop setup launcher metadata.")
        return files.firstOrNull { it.kind == "workshop-setup-launcher" && it.fileName == fileName }
            ?: error("Release manifest does not list workshop setup launcher `$fileName`.")
    }

    fun workshopSetupToolFiles(): List<SignalSlingerReleaseFile> {
        return files.filter { it.kind == "workshop-setup-tool" }
    }
}

data class SignalSlingerUpdateImage(
    val fileName: String,
    val startAddress: Int,
    val bytesInImage: Int,
)

data class SignalSlingerFirstInstallImage(
    val fileName: String,
    val bytesInImage: Int,
)

data class SignalSlingerUpdateSettings(
    val appBaud: Int,
    val updateBaud: Int,
    val appInfoCommand: String,
    val appUpdateCommand: String,
    val bootloaderEntryCommand: String,
    val pageBytes: Int,
    val protocolVersion: Int,
    val bootloaderVersion: String,
    val appStartAddress: Int,
    val flashBytes: Int,
)

data class SignalSlingerWorkshopSetup(
    val setupHelperFileName: String,
    val setupLauncherFileName: String?,
    val provisioningScriptFileName: String?,
    val serialValidationScriptFileName: String?,
    val supportedProgrammers: List<String>,
    val bootSectionPages: Int,
    val fuseBootSize: String,
    val fuseCodeSize: String,
)

data class SignalSlingerReleaseFile(
    val fileName: String,
    val kind: String,
    val purpose: String?,
    val sizeBytes: Int?,
    val sha256: String,
)

data class SignalSlingerBootloaderIdentity(
    val product: String,
    val bootloaderVersion: String,
    val protocolVersion: Int,
    val appStartAddress: Int,
    val pageBytes: Int,
    val flashBytes: Int,
    val baud: Int,
    val bootSectionPages: Int?,
    val writableStartAddress: Int?,
    val writableEndAddress: Int?,
    val commands: Set<Char>,
) {
    fun validateForRelease(release: SignalSlingerReleaseInfo) {
        require(product.startsWith("SignalSlinger")) { "Update device did not identify as SignalSlinger." }
        require(protocolVersion == release.serialSlinger.protocolVersion) {
            "Update protocol mismatch: device proto=$protocolVersion, package proto=${release.serialSlinger.protocolVersion}."
        }
        require(appStartAddress == release.serialSlinger.appStartAddress) {
            "Update start address mismatch: device app=${appStartAddress.toHex32()}, package app=${release.serialSlinger.appStartAddress.toHex32()}."
        }
        require(pageBytes == release.serialSlinger.pageBytes) {
            "Update page size mismatch: device page=$pageBytes, package page=${release.serialSlinger.pageBytes}."
        }
        require(flashBytes == release.serialSlinger.flashBytes) {
            "Update flash size mismatch: device flash=$flashBytes, package flash=${release.serialSlinger.flashBytes}."
        }
        require(baud == release.serialSlinger.updateBaud) {
            "Update baud mismatch: device baud=$baud, package baud=${release.serialSlinger.updateBaud}."
        }
        require(commands.containsAll(setOf('E', 'W', 'C'))) {
            "Update device does not support required update commands E, W, and C."
        }
        require(release.serialSlinger.bootloaderEntryCommand.singleOrNull()?.let(commands::contains) != false) {
            "Update device does not support the package bootloader entry command `${release.serialSlinger.bootloaderEntryCommand}`."
        }
    }

    fun validateWritableRangeForImage(imageAddresses: Set<Int>) {
        val start = writableStartAddress
        val end = writableEndAddress
        require(start != null && end != null) {
            "Update device did not report its writable flash range."
        }
        val lowestAddress = imageAddresses.minOrNull() ?: return
        val highestAddress = imageAddresses.maxOrNull() ?: return
        require(lowestAddress >= start && highestAddress <= end) {
            "Update image range ${lowestAddress.toHex32()}-${highestAddress.toHex32()} is outside bootloader writable range ${start.toHex32()}-${end.toHex32()}."
        }
    }
}

data class SignalSlingerAppInfo(
    val product: String? = null,
    val softwareVersion: String? = null,
    val hardwareBuild: String? = null,
    val appStartAddress: Int? = null,
    val updateBaud: Int? = null,
    val appUpdateCommand: String? = null,
) {
    fun validateForRelease(
        release: SignalSlingerReleaseInfo,
        requireHardwareMatch: Boolean = true,
    ) {
        require(SignalSlingerFirmwareUpdate.supportsBootloaderUpdate(softwareVersion)) {
            "Connected SignalSlinger firmware ${softwareVersion.orEmpty().ifBlank { "unknown" }} does not support firmware updates. Firmware 2.0.0 or newer is required."
        }
        require(product == release.product) {
            "Connected device product `${product.orEmpty()}` does not match package product `${release.product}`."
        }
        require(!requireHardwareMatch || hardwareMatchesBoard(hardwareBuild, release.board)) {
            "Connected firmware build ${hardwareBuild.orEmpty().ifBlank { "unknown" }} does not match package board `${release.board}`."
        }
        require(release.update.fileName.contains(release.board)) {
            "Update file `${release.update.fileName}` does not identify package board `${release.board}`."
        }
        require(appStartAddress == release.serialSlinger.appStartAddress) {
            "Connected app start ${appStartAddress?.toHex32() ?: "unknown"} does not match package app ${release.serialSlinger.appStartAddress.toHex32()}."
        }
        require(updateBaud == release.serialSlinger.updateBaud) {
            "Connected update baud ${updateBaud ?: "unknown"} does not match package baud ${release.serialSlinger.updateBaud}."
        }
        require(appUpdateCommand == release.serialSlinger.appUpdateCommand) {
            "Connected update command `${appUpdateCommand.orEmpty()}` does not match package update command `${release.serialSlinger.appUpdateCommand}`."
        }
    }

    fun validateUpdatedForRelease(release: SignalSlingerReleaseInfo) {
        validateForRelease(release)
        require(softwareVersion == release.version) {
            "Updated SignalSlinger reported firmware ${softwareVersion.orEmpty().ifBlank { "unknown" }} instead of ${release.version}."
        }
    }
}

data class SignalSlingerFirmwarePage(
    val address: Int,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        return other is SignalSlingerFirmwarePage &&
            address == other.address &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return 31 * address + bytes.contentHashCode()
    }
}

enum class SignalSlingerFirmwareOperationKind {
    ERASE,
    WRITE,
    CRC,
    RUN,
}

data class SignalSlingerFirmwareOperation(
    val kind: SignalSlingerFirmwareOperationKind,
    val address: Int? = null,
    val bytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        return other is SignalSlingerFirmwareOperation &&
            kind == other.kind &&
            address == other.address &&
            ((bytes == null && other.bytes == null) || (bytes != null && other.bytes != null && bytes.contentEquals(other.bytes)))
    }

    override fun hashCode(): Int {
        return 31 * (31 * kind.hashCode() + (address ?: 0)) + (bytes?.contentHashCode() ?: 0)
    }
}

data class SignalSlingerFirmwarePlan(
    val pages: List<SignalSlingerFirmwarePage>,
    val operations: List<SignalSlingerFirmwareOperation>,
) {
    val pageCount: Int
        get() = pages.size
}

data class SignalSlingerPageCrcResponse(
    val address: Int,
    val crc: Int,
)

data class SignalSlingerFirmwareUpdateProgress(
    val stage: String,
    val completedPages: Int,
    val totalPages: Int,
    val detail: String? = null,
)

interface SignalSlingerFirmwareUpdateTransport {
    fun connect(baudRate: Int)

    fun reconfigureBaudRate(baudRate: Int): Boolean = false

    fun disconnect()

    fun writeAscii(text: String)

    fun writeBytes(bytes: ByteArray)

    fun readLines(timeoutMs: Long): List<String>
}

object SignalSlingerFirmwareUpdate {
    private val identityPattern = Regex("""^(SignalSlinger)\s+(\S+)\s+(.*)$""")
    private val crcResponsePattern = Regex("""^OK\s+crc\s+0x([0-9A-Fa-f]{1,8})\s+([0-9A-Fa-f]{1,4})$""")
    private val writableRangePattern = Regex("""^(0x[0-9A-Fa-f]+|\d+)-(0x[0-9A-Fa-f]+|\d+)$""")
    private val versionNumberPattern = Regex("""^\s*(\d+)(?:\.(\d+))?(?:\.(\d+))?.*$""")
    private val appInfoPattern = Regex("""\*\s+INF\s+(.+)$""")
    private val versionPattern = Regex("""^\*\s+SW Ver:\s*(.+?)\s+HW Build:\s*(.+)$""")

    fun supportsBootloaderUpdate(softwareVersion: String?): Boolean {
        val match = softwareVersion?.let(versionNumberPattern::matchEntire) ?: return false
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt() ?: 0
        val patch = match.groupValues[3].takeIf(String::isNotEmpty)?.toInt() ?: 0
        return compareVersion(major, minor, patch, 2, 0, 0) >= 0
    }

    fun compareVersionStrings(
        first: String?,
        second: String?,
    ): Int {
        val firstParts = parseVersionParts(first)
        val secondParts = parseVersionParts(second)
        return compareVersion(
            firstParts[0],
            firstParts[1],
            firstParts[2],
            secondParts[0],
            secondParts[1],
            secondParts[2],
        )
    }

    fun hardwareMatchesBoard(
        hardwareBuild: String?,
        board: String,
    ): Boolean = hardwareBuildMatchesBoard(hardwareBuild, board)

    fun parseReleaseInfo(jsonText: String): SignalSlingerReleaseInfo {
        val root = JsonValue.parse(jsonText.trimStart('\uFEFF')).asObject()
        val format = root.requiredString("format")
        require(format == "signalslinger-release-info-v1") {
            "Unsupported SignalSlinger release manifest format `$format`."
        }
        val update = root.requiredObject("update")
        val firstInstall = root.optionalObject("firstInstall")
        val serialSlinger = root.requiredObject("serialSlinger")
        val workshopSetup = root.optionalObject("workshopSetup")
        val files = root.requiredArray("files").map { item ->
            val file = item.asObject()
            SignalSlingerReleaseFile(
                fileName = file.requiredString("fileName"),
                kind = file.requiredString("kind"),
                purpose = file.optionalString("purpose"),
                sizeBytes = file.optionalInt("sizeBytes"),
                sha256 = file.requiredString("sha256").lowercase(),
            )
        }
        return SignalSlingerReleaseInfo(
            format = format,
            product = root.requiredString("product"),
            version = root.requiredString("version"),
            board = root.requiredString("board"),
            update = SignalSlingerUpdateImage(
                fileName = update.requiredString("fileName"),
                startAddress = parseIntLiteral(update.requiredString("startAddress")),
                bytesInImage = update.requiredInt("bytesInImage"),
            ),
            firstInstall = firstInstall?.let {
                SignalSlingerFirstInstallImage(
                    fileName = it.requiredString("fileName"),
                    bytesInImage = it.requiredInt("bytesInImage"),
                )
            },
            serialSlinger = SignalSlingerUpdateSettings(
                appBaud = serialSlinger.requiredInt("appBaud"),
                updateBaud = serialSlinger.requiredInt("updateBaud"),
                appInfoCommand = serialSlinger.requiredString("appInfoCommand"),
                appUpdateCommand = serialSlinger.requiredString("appUpdateCommand"),
                bootloaderEntryCommand = serialSlinger.requiredString("bootloaderEntryCommand"),
                pageBytes = serialSlinger.requiredInt("pageBytes"),
                protocolVersion = serialSlinger.requiredInt("protocolVersion"),
                bootloaderVersion = serialSlinger.requiredString("bootloaderVersion"),
                appStartAddress = parseIntLiteral(serialSlinger.requiredString("appStartAddress")),
                flashBytes = serialSlinger.requiredInt("flashBytes"),
            ),
            workshopSetup = workshopSetup?.let {
                SignalSlingerWorkshopSetup(
                    setupHelperFileName = it.requiredString("setupHelperFileName"),
                    setupLauncherFileName = it.optionalString("setupLauncherFileName"),
                    provisioningScriptFileName = it.optionalString("provisioningScriptFileName"),
                    serialValidationScriptFileName = it.optionalString("serialValidationScriptFileName"),
                    supportedProgrammers = it.optionalArray("supportedProgrammers")?.map { item -> item.asString() }.orEmpty(),
                    bootSectionPages = it.requiredInt("bootSectionPages"),
                    fuseBootSize = it.requiredString("fuseBootSize"),
                    fuseCodeSize = it.requiredString("fuseCodeSize"),
                )
            },
            files = files,
        )
    }

    fun verifyReleaseFileHash(
        releaseFile: SignalSlingerReleaseFile,
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

    fun parseIntelHexPages(
        hexText: String,
        appStartAddress: Int,
        flashBytes: Int = DefaultFlashBytes,
        pageBytes: Int = DefaultPageBytes,
    ): List<SignalSlingerFirmwarePage> {
        val image = parseIntelHexImage(hexText, appStartAddress, flashBytes)
        require(image.keys.any { it in appStartAddress until (appStartAddress + pageBytes) }) {
            "Update image does not contain the reset-vector page at ${appStartAddress.toHex32()}."
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

    fun buildUpdatePlan(
        pages: List<SignalSlingerFirmwarePage>,
        appStartAddress: Int,
    ): SignalSlingerFirmwarePlan {
        val resetVectorPage = pages.firstOrNull { it.address == appStartAddress }
            ?: error("Update image does not contain the reset-vector page at ${appStartAddress.toHex32()}.")
        val otherPages = pages.filterNot { it.address == appStartAddress }.sortedBy { it.address }
        val operations = mutableListOf<SignalSlingerFirmwareOperation>()

        operations += SignalSlingerFirmwareOperation(SignalSlingerFirmwareOperationKind.ERASE, resetVectorPage.address)
        otherPages.forEach { page ->
            operations += SignalSlingerFirmwareOperation(SignalSlingerFirmwareOperationKind.ERASE, page.address)
            operations += SignalSlingerFirmwareOperation(SignalSlingerFirmwareOperationKind.WRITE, page.address, page.bytes)
            operations += SignalSlingerFirmwareOperation(SignalSlingerFirmwareOperationKind.CRC, page.address, page.bytes)
        }
        operations += SignalSlingerFirmwareOperation(SignalSlingerFirmwareOperationKind.WRITE, resetVectorPage.address, resetVectorPage.bytes)
        operations += SignalSlingerFirmwareOperation(SignalSlingerFirmwareOperationKind.CRC, resetVectorPage.address, resetVectorPage.bytes)
        operations += SignalSlingerFirmwareOperation(SignalSlingerFirmwareOperationKind.RUN)

        return SignalSlingerFirmwarePlan(pages = listOf(resetVectorPage) + otherPages, operations = operations)
    }

    fun eraseFrame(address: Int): ByteArray {
        return frame('E', littleEndian32(address))
    }

    fun writeFrame(
        address: Int,
        pageBytes: ByteArray,
    ): ByteArray {
        require(pageBytes.size == DefaultPageBytes) { "Write frame requires a 512-byte page." }
        return frame('W', littleEndian32(address) + pageBytes)
    }

    fun pageCrcFrame(address: Int): ByteArray {
        return frame('C', littleEndian32(address))
    }

    fun crc16CcittFalse(bytes: ByteArray): Int {
        var crc = 0xFFFF
        bytes.forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc =
                    if ((crc and 0x8000) != 0) {
                        (crc shl 1) xor 0x1021
                    } else {
                        crc shl 1
                    }
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    fun parseIdentityLine(line: String): SignalSlingerBootloaderIdentity? {
        val match = identityPattern.find(line.trim()) ?: return null
        val fields = match.groupValues[3]
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val parts = token.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
        if (!fields.keys.containsAll(setOf("proto", "app", "page", "flash", "baud", "cmds"))) {
            return null
        }
        val writableRange = parseWritableRange(fields["write"])
        return SignalSlingerBootloaderIdentity(
            product = match.groupValues[1],
            bootloaderVersion = match.groupValues[2],
            protocolVersion = fields.requiredIntToken("proto"),
            appStartAddress = parseIntLiteral(fields.requiredToken("app")),
            pageBytes = fields.requiredIntToken("page"),
            flashBytes = fields.requiredIntToken("flash"),
            baud = fields.requiredIntToken("baud"),
            bootSectionPages = fields["boot"]?.toIntOrNull(),
            writableStartAddress = writableRange?.first,
            writableEndAddress = writableRange?.last,
            commands = fields.requiredToken("cmds").split(",").mapNotNull { it.singleOrNull() }.toSet(),
        )
    }

    fun parsePageCrcResponse(line: String): SignalSlingerPageCrcResponse? {
        val match = crcResponsePattern.find(line.trim()) ?: return null
        return SignalSlingerPageCrcResponse(
            address = match.groupValues[1].toInt(16),
            crc = match.groupValues[2].toInt(16),
        )
    }

    fun parseAppInfo(lines: List<String>): SignalSlingerAppInfo? {
        val fields = mutableMapOf<String, String>()
        lines.forEach { line ->
            appInfoPattern.findAll(line.trim()).forEach { match ->
                parseKeyValueFields(match.groupValues[1]).forEach { (key, value) -> fields[key] = value }
            }
        }
        if (fields.isNotEmpty()) {
            return SignalSlingerAppInfo(
                product = fields["product"],
                softwareVersion = fields["sw"],
                hardwareBuild = fields["hw"],
                appStartAddress = fields["app"]?.let(::parseIntLiteral),
                updateBaud = fields["baud"]?.toIntOrNull(),
                appUpdateCommand = fields["update"],
            )
        }
        return lines.firstNotNullOfOrNull { line ->
            versionPattern.matchEntire(line.trim())?.let { match ->
                SignalSlingerAppInfo(
                    softwareVersion = match.groupValues[1].trim(),
                    hardwareBuild = match.groupValues[2].trim(),
                )
            }
        }
    }

    fun performUpdate(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: SignalSlingerReleaseInfo,
        hexText: String,
        recoverAlreadyWaiting: Boolean = false,
        allowAppHardwareMismatch: Boolean = false,
        progress: (SignalSlingerFirmwareUpdateProgress) -> Unit = {},
    ) {
        progress(SignalSlingerFirmwareUpdateProgress("Preparing update", 0, 0))
        validateReleaseManifest(release)
        val image = parseIntelHexImage(
            hexText = hexText,
            appStartAddress = release.serialSlinger.appStartAddress,
            flashBytes = release.serialSlinger.flashBytes,
        )
        require(image.keys.minOrNull() == release.update.startAddress) {
            "Update image starts at ${image.keys.minOrNull()?.toHex32() ?: "unknown"} but release metadata says ${release.update.startAddress.toHex32()}."
        }
        require(image.size == release.update.bytesInImage) {
            "Update image byte count mismatch: manifest says ${release.update.bytesInImage}, HEX contains ${image.size}."
        }
        val pages = pagesFromImage(
            image = image,
            appStartAddress = release.serialSlinger.appStartAddress,
            pageBytes = release.serialSlinger.pageBytes,
        )
        val plan = buildUpdatePlan(pages, release.serialSlinger.appStartAddress)

        val identity =
            enterUpdateMode(
                transport = transport,
                release = release,
                recoverAlreadyWaiting = recoverAlreadyWaiting,
                allowAppHardwareMismatch = allowAppHardwareMismatch,
                progress = progress,
        )
        identity.validateForRelease(release)
        identity.validateWritableRangeForImage(image.keys)

        var lastFailure: Throwable? = null
        var transferCompleted = false
        for (attempt in 1..MaxTransferAttempts) {
            try {
                transferPlan(transport, plan, progress)
                transferCompleted = true
                break
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt >= MaxTransferAttempts) {
                    break
                }
                progress(
                    SignalSlingerFirmwareUpdateProgress(
                        "Preparing update",
                        0,
                        plan.pageCount,
                        "Retrying update after communication failed: ${failure.message.orEmpty()}",
                    ),
                )
                try {
                    transport.disconnect()
                } catch (_: Throwable) {
                }
                connectUpdateModeAndWaitForIdentity(transport, release, progress)?.validateForRelease(release)
                    ?: error("SignalSlinger did not respond in update mode after retry.")
            }
        }
        if (!transferCompleted) {
            throw lastFailure ?: IllegalStateException("SignalSlinger update failed.")
        }
        confirmUpdatedApp(transport, release, progress)
    }

    private fun transferPlan(
        transport: SignalSlingerFirmwareUpdateTransport,
        plan: SignalSlingerFirmwarePlan,
        progress: (SignalSlingerFirmwareUpdateProgress) -> Unit,
    ) {
        var completedPages = 0
        plan.operations.forEach { operation ->
            when (operation.kind) {
                SignalSlingerFirmwareOperationKind.ERASE -> {
                    val address = requireNotNull(operation.address)
                    transport.writeBytes(eraseFrame(address))
                    requireOk(transport.readLines(1_500), "OK erase")
                }
                SignalSlingerFirmwareOperationKind.WRITE -> {
                    val address = requireNotNull(operation.address)
                    val bytes = requireNotNull(operation.bytes)
                    transport.writeBytes(writeFrame(address, bytes))
                    requireOk(transport.readLines(2_500), "OK write")
                }
                SignalSlingerFirmwareOperationKind.CRC -> {
                    val address = requireNotNull(operation.address)
                    val bytes = requireNotNull(operation.bytes)
                    progress(SignalSlingerFirmwareUpdateProgress("Verifying update", completedPages, plan.pageCount, address.toHex32()))
                    transport.writeBytes(pageCrcFrame(address))
                    val response = transport.readLines(1_500).firstNotNullOfOrNull(::parsePageCrcResponse)
                        ?: error("SignalSlinger did not return a page verification response for ${address.toHex32()}.")
                    require(response.address == address) {
                        "SignalSlinger verified ${response.address.toHex32()} while ${address.toHex32()} was expected."
                    }
                    val expected = crc16CcittFalse(bytes)
                    require(response.crc == expected) {
                        "SignalSlinger verification failed at ${address.toHex32()}: expected ${expected.toHex16()}, got ${response.crc.toHex16()}."
                    }
                    completedPages++
                    progress(SignalSlingerFirmwareUpdateProgress("Sending update", completedPages, plan.pageCount, address.toHex32()))
                }
                SignalSlingerFirmwareOperationKind.RUN -> {
                    progress(SignalSlingerFirmwareUpdateProgress("Restarting SignalSlinger", completedPages, plan.pageCount))
                    transport.writeAscii("R")
                }
            }
        }
    }

    private fun confirmUpdatedApp(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: SignalSlingerReleaseInfo,
        progress: (SignalSlingerFirmwareUpdateProgress) -> Unit,
    ) {
        progress(SignalSlingerFirmwareUpdateProgress("Restarting SignalSlinger", 0, 0, "Checking SignalSlinger after restart"))
        var lastFailure: Throwable? = null
        runCatching { transport.readLines(2_000) }
        repeat(8) {
            try {
                transport.disconnect()
            } catch (_: Throwable) {
            }
            transport.connect(release.serialSlinger.appBaud)
            val appInfo = readSupportedAppInfo(transport, release)
                ?: error("SignalSlinger update was sent, but SerialSlinger could not confirm the updated firmware after restart.")
            try {
                appInfo.validateUpdatedForRelease(release)
                return
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: IllegalStateException("SignalSlinger update was sent, but SerialSlinger could not confirm the updated firmware after restart.")
    }

    private fun enterUpdateMode(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: SignalSlingerReleaseInfo,
        recoverAlreadyWaiting: Boolean,
        allowAppHardwareMismatch: Boolean,
        progress: (SignalSlingerFirmwareUpdateProgress) -> Unit,
    ): SignalSlingerBootloaderIdentity {
        if (!recoverAlreadyWaiting) {
            transport.connect(release.serialSlinger.appBaud)
            val infoResult = readAppInfoOrBootloaderIdentity(transport, release)
            infoResult.identity?.let { return it }
            val appInfo = infoResult.appInfo
                ?: error("SerialSlinger could not confirm SignalSlinger firmware update support.")
            appInfo.validateForRelease(release, requireHardwareMatch = !allowAppHardwareMismatch)
            transport.writeAscii("${release.serialSlinger.appUpdateCommand}\r")
            val appLines = transport.readLines(300)
            val alreadyInUpdate = appLines.firstNotNullOfOrNull(::parseIdentityLine)
            if (alreadyInUpdate != null) {
                return alreadyInUpdate
            }
            val acknowledgedUpdate = appLines.any { it.contains("Bootloader update") }

            progress(SignalSlingerFirmwareUpdateProgress("Preparing update", 0, 0, "Waiting for SignalSlinger update mode"))
            if (transport.reconfigureBaudRate(release.serialSlinger.updateBaud)) {
                waitForBootloaderIdentity(transport, release)?.let { return it }
            }
            transport.disconnect()

            connectUpdateModeAndWaitForIdentity(transport, release, progress)?.let { return it }
            transport.disconnect()

            if (!acknowledgedUpdate) {
                progress(
                    SignalSlingerFirmwareUpdateProgress(
                        "Preparing update",
                        0,
                        0,
                        "${release.serialSlinger.appUpdateCommand} did not acknowledge; trying reset entry.",
                    ),
                )
                transport.connect(release.serialSlinger.appBaud)
                transport.readLines(300)
                transport.writeAscii("**\r")
                transport.readLines(100)
                transport.writeAscii("RST\n")
                transport.readLines(50)
                transport.disconnect()
            }
        }

        progress(SignalSlingerFirmwareUpdateProgress("Preparing update", 0, 0, "Waiting for SignalSlinger update mode"))
        connectUpdateModeAndWaitForIdentity(transport, release, progress)?.let { return it }
        error("SignalSlinger did not enter update mode.")
    }

    private fun connectUpdateModeAndWaitForIdentity(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: SignalSlingerReleaseInfo,
        progress: (SignalSlingerFirmwareUpdateProgress) -> Unit,
    ): SignalSlingerBootloaderIdentity? {
        var lastFailure: Throwable? = null
        repeat(4) { attempt ->
            try {
                transport.disconnect()
            } catch (_: Throwable) {
            }
            try {
                Thread.sleep(UpdateModeReconnectSettleMs)
                transport.connect(release.serialSlinger.updateBaud)
                waitForBootloaderIdentity(transport, release)?.let { return it }
                if (attempt == 0) {
                    progress(
                        SignalSlingerFirmwareUpdateProgress(
                            "Preparing update",
                            0,
                            0,
                            "Retrying update connection after SignalSlinger did not respond.",
                        ),
                    )
                }
            } catch (failure: Throwable) {
                lastFailure = failure
                progress(
                    SignalSlingerFirmwareUpdateProgress(
                        "Preparing update",
                        0,
                        0,
                        "Retrying update connection after communication failed: ${failure.message.orEmpty()}",
                    ),
                )
            }
        }
        lastFailure?.let { throw it }
        return null
    }

    private fun readSupportedAppInfo(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: SignalSlingerReleaseInfo,
    ): SignalSlingerAppInfo? {
        val result = readAppInfoOrBootloaderIdentity(transport, release)
        if (result.identity != null) {
            return null
        }
        return result.appInfo
    }

    private fun readAppInfoOrBootloaderIdentity(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: SignalSlingerReleaseInfo,
    ): AppInfoReadResult {
        val allLines = mutableListOf<String>()
        repeat(3) {
            transport.readLines(600)
            transport.writeAscii("**\r")
            allLines += transport.readLines(250)
            transport.writeAscii("VER\r")
            allLines += transport.readLines(1_200)
            transport.writeAscii("${release.serialSlinger.appInfoCommand}\r")
            allLines += transport.readLines(1_200)
            allLines.firstNotNullOfOrNull(::parseIdentityLine)?.let { return AppInfoReadResult(identity = it) }
            parseAppInfo(allLines)?.let { return AppInfoReadResult(appInfo = it) }
        }
        return AppInfoReadResult()
    }

    private fun waitForBootloaderIdentity(
        transport: SignalSlingerFirmwareUpdateTransport,
        release: SignalSlingerReleaseInfo,
    ): SignalSlingerBootloaderIdentity? {
        repeat(12) {
            transport.writeAscii(release.serialSlinger.bootloaderEntryCommand)
            val entryLines = transport.readLines(500)
            entryLines.firstNotNullOfOrNull(::parseIdentityLine)?.let { return it }
            if (entryLines.any { it.trim() == "BOOT" }) {
                transport.writeAscii("?")
                val infoLines = transport.readLines(500)
                infoLines.firstNotNullOfOrNull(::parseIdentityLine)?.let { return it }
            }
            transport.writeAscii("?")
            val queryLines = transport.readLines(500)
            queryLines.firstNotNullOfOrNull(::parseIdentityLine)?.let { return it }
            if (queryLines.any { it.trim() == "BOOT" }) {
                transport.writeAscii("?")
                val infoLines = transport.readLines(500)
                infoLines.firstNotNullOfOrNull(::parseIdentityLine)?.let { return it }
            }
        }
        return null
    }

    private fun parseIntelHexImage(
        hexText: String,
        appStartAddress: Int,
        flashBytes: Int,
    ): Map<Int, Byte> {
        var upperAddress = 0
        val image = mutableMapOf<Int, Byte>()
        hexText.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                return@forEachIndexed
            }
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
                        require(address in appStartAddress until flashBytes) {
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
        appStartAddress: Int,
        pageBytes: Int,
    ): List<SignalSlingerFirmwarePage> {
        require(image.keys.any { it in appStartAddress until (appStartAddress + pageBytes) }) {
            "Update image does not contain the reset-vector page at ${appStartAddress.toHex32()}."
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

    private fun validateReleaseManifest(release: SignalSlingerReleaseInfo) {
        require(release.product == "SignalSlinger") {
            "Update package product `${release.product}` is not supported."
        }
        require(release.update.startAddress == release.serialSlinger.appStartAddress) {
            "Update package start address ${release.update.startAddress.toHex32()} does not match app address ${release.serialSlinger.appStartAddress.toHex32()}."
        }
        require(release.serialSlinger.pageBytes == DefaultPageBytes) {
            "Update package page size ${release.serialSlinger.pageBytes} is not supported by this SerialSlinger build."
        }
        require(release.serialSlinger.appStartAddress > 0 && release.serialSlinger.appStartAddress < release.serialSlinger.flashBytes) {
            "Update package app address ${release.serialSlinger.appStartAddress.toHex32()} is outside the supported flash range."
        }
        require(release.serialSlinger.appStartAddress % release.serialSlinger.pageBytes == 0) {
            "Update package app address ${release.serialSlinger.appStartAddress.toHex32()} is not page-aligned."
        }
        require(release.serialSlinger.flashBytes <= DefaultFlashBytes) {
            "Update package flash size ${release.serialSlinger.flashBytes} is larger than supported."
        }
        require(release.serialSlinger.bootloaderEntryCommand.length == 1) {
            "Update package bootloader entry command must be a single byte."
        }
        require(release.update.fileName.contains(release.board)) {
            "Update file `${release.update.fileName}` does not identify package board `${release.board}`."
        }
        require(release.updateFile().sha256.matches(Regex("[0-9a-f]{64}"))) {
            "Update file `${release.update.fileName}` has an invalid SHA-256 value in the release manifest."
        }
    }

    private fun frame(
        command: Char,
        body: ByteArray,
    ): ByteArray {
        val withoutCrc = byteArrayOf(command.code.toByte()) + body
        val crc = crc16CcittFalse(withoutCrc)
        return withoutCrc + littleEndian16(crc)
    }

    private fun requireOk(
        lines: List<String>,
        expected: String,
    ) {
        lines.firstOrNull { it.startsWith("ERR") }?.let { error("SignalSlinger update failed: $it") }
        require(lines.any { it.trim() == expected }) {
            "SignalSlinger did not return `$expected`. Last response: ${lines.lastOrNull().orEmpty()}"
        }
    }

    private fun parseIntLiteral(text: String): Int {
        val normalized = text.trim()
        return if (normalized.startsWith("0x", ignoreCase = true)) {
            normalized.drop(2).toInt(16)
        } else {
            normalized.toInt()
        }
    }

    private fun parseWritableRange(text: String?): IntRange? {
        val match = text?.trim()?.let(writableRangePattern::matchEntire) ?: return null
        return parseIntLiteral(match.groupValues[1])..parseIntLiteral(match.groupValues[2])
    }

    private fun parseKeyValueFields(text: String): Map<String, String> {
        return text
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val parts = token.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    private fun compareVersion(
        major: Int,
        minor: Int,
        patch: Int,
        requiredMajor: Int,
        requiredMinor: Int,
        requiredPatch: Int,
    ): Int {
        return when {
            major != requiredMajor -> major.compareTo(requiredMajor)
            minor != requiredMinor -> minor.compareTo(requiredMinor)
            else -> patch.compareTo(requiredPatch)
        }
    }

    private fun littleEndian16(value: Int): ByteArray {
        return byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())
    }

    private fun littleEndian32(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )
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

    private fun Map<String, String>.requiredToken(key: String): String = this[key] ?: error("Bootloader identity is missing `$key`.")

    private fun Map<String, String>.requiredIntToken(key: String): Int = requiredToken(key).toInt()

    private data class AppInfoReadResult(
        val appInfo: SignalSlingerAppInfo? = null,
        val identity: SignalSlingerBootloaderIdentity? = null,
    )
}

private fun hardwareMatchesBoard(
    hardwareBuild: String?,
    board: String,
): Boolean = hardwareBuildMatchesBoard(hardwareBuild, board)

private fun hardwareBuildMatchesBoard(
    hardwareBuild: String?,
    board: String,
): Boolean {
    val hardwareToken = normalizeHardwareBoardToken(hardwareBuild ?: return false)
    val boardToken = normalizeHardwareBoardToken(board)
    return hardwareToken.isNotEmpty() && hardwareToken == boardToken
}

private fun normalizeHardwareBoardToken(text: String): String =
    normalizeBoardToken(
        text
            .removePrefix("Board-")
            .removePrefix("HW-"),
    )

private fun normalizeBoardToken(text: String): String {
    return text.lowercase().filter { it.isLetterOrDigit() }
}

private fun parseVersionParts(version: String?): IntArray {
    val match = version?.let(Regex("""^\s*(\d+)(?:\.(\d+))?(?:\.(\d+))?.*$""")::matchEntire)
    return intArrayOf(
        match?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        match?.groupValues?.get(2)?.takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0,
        match?.groupValues?.get(3)?.takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0,
    )
}

private sealed class JsonValue {
    data class Obj(val value: Map<String, JsonValue>) : JsonValue()
    data class Arr(val value: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Int) : JsonValue()
    data object Bool : JsonValue()
    data object Null : JsonValue()

    fun asObject(): Map<String, JsonValue> = (this as? Obj)?.value ?: error("Expected JSON object.")

    fun asArray(): List<JsonValue> = (this as? Arr)?.value ?: error("Expected JSON array.")

    fun asString(): String = (this as? Str)?.value ?: error("Expected JSON string.")

    fun asInt(): Int = (this as? Num)?.value ?: error("Expected JSON integer.")

    companion object {
        fun parse(text: String): JsonValue = Parser(text).parse()
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): JsonValue {
            val value = parseValue()
            skipWhitespace()
            require(index == text.length) { "Unexpected trailing JSON content." }
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> parseLiteral("true", Bool)
                'f' -> parseLiteral("false", Bool)
                'n' -> parseLiteral("null", Null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): JsonValue {
            expect('{')
            val values = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (consume('}')) {
                return Obj(values)
            }
            do {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                values[key] = parseValue()
                skipWhitespace()
            } while (consume(','))
            expect('}')
            return Obj(values)
        }

        private fun parseArray(): JsonValue {
            expect('[')
            val values = mutableListOf<JsonValue>()
            skipWhitespace()
            if (consume(']')) {
                return Arr(values)
            }
            do {
                values += parseValue()
                skipWhitespace()
            } while (consume(','))
            expect(']')
            return Arr(values)
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                when (ch) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        val escaped = text[index++]
                        builder.append(
                            when (escaped) {
                                '"', '\\', '/' -> escaped
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    val hex = text.substring(index, index + 4)
                                    index += 4
                                    hex.toInt(16).toChar()
                                }
                                else -> error("Invalid JSON escape `\\$escaped`.")
                            },
                        )
                    }
                    else -> builder.append(ch)
                }
            }
            error("Unterminated JSON string.")
        }

        private fun parseNumber(): JsonValue {
            val start = index
            if (peek() == '-') {
                index++
            }
            while (index < text.length && text[index].isDigit()) {
                index++
            }
            require(index > start) { "Expected JSON value." }
            return Num(text.substring(start, index).toInt())
        }

        private fun parseLiteral(
            literal: String,
            value: JsonValue,
        ): JsonValue {
            require(text.startsWith(literal, index)) { "Expected JSON literal `$literal`." }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) {
                index++
            }
        }

        private fun peek(): Char = text.getOrNull(index) ?: error("Unexpected end of JSON.")

        private fun expect(ch: Char) {
            require(peek() == ch) { "Expected `$ch` in JSON." }
            index++
        }

        private fun consume(ch: Char): Boolean {
            if (index < text.length && text[index] == ch) {
                index++
                return true
            }
            return false
        }
    }
}

private object Sha256 {
    private val initialHash = intArrayOf(
        0x6a09e667,
        0xbb67ae85.toInt(),
        0x3c6ef372,
        0xa54ff53a.toInt(),
        0x510e527f,
        0x9b05688c.toInt(),
        0x1f83d9ab,
        0x5be0cd19,
    )
    private val k = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun digestHex(bytes: ByteArray): String {
        val hash = initialHash.copyOf()
        val padded = pad(bytes)
        val w = IntArray(64)
        for (chunkOffset in padded.indices step 64) {
            for (i in 0 until 16) {
                val offset = chunkOffset + i * 4
                w[i] = ((padded[offset].toInt() and 0xFF) shl 24) or
                    ((padded[offset + 1].toInt() and 0xFF) shl 16) or
                    ((padded[offset + 2].toInt() and 0xFF) shl 8) or
                    (padded[offset + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = hash[0]
            var b = hash[1]
            var c = hash[2]
            var d = hash[3]
            var e = hash[4]
            var f = hash[5]
            var g = hash[6]
            var h = hash[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + k[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            hash[0] += a
            hash[1] += b
            hash[2] += c
            hash[3] += d
            hash[4] += e
            hash[5] += f
            hash[6] += g
            hash[7] += h
        }
        return hash.joinToString("") { word -> word.toUInt().toString(16).padStart(8, '0') }
    }

    private fun pad(bytes: ByteArray): ByteArray {
        val bitLength = bytes.size.toLong() * 8L
        val paddedLength = (((bytes.size + 9 + 63) / 64) * 64)
        val padded = ByteArray(paddedLength)
        bytes.copyInto(padded)
        padded[bytes.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLength - 1 - i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
        }
        return padded
    }
}
