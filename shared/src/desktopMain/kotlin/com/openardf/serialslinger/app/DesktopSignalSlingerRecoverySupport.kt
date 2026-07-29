package com.openardf.serialslinger.app

import com.openardf.serialslinger.protocol.SignalSlingerFirmwareUpdate

object DesktopSignalSlingerRecoverySupport {
    val supportedBoards: List<String> = listOf("HW-3.4", "HW-3.5")

    fun preferredBoard(reportedHardwareBuild: String?): String? {
        return supportedBoards.firstOrNull { board ->
            SignalSlingerFirmwareUpdate.hardwareMatchesBoard(reportedHardwareBuild, board)
        }
    }

    fun hardwareBuildForBoard(board: String): String = board.substringAfter("HW-", board)

    fun packageMatchesBoard(
        selectedBoard: String,
        packageBoard: String,
    ): Boolean {
        return SignalSlingerFirmwareUpdate.hardwareMatchesBoard(
            hardwareBuildForBoard(selectedBoard),
            packageBoard,
        )
    }
}
