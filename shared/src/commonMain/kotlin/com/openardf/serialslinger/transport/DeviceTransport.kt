package com.openardf.serialslinger.transport

import com.openardf.serialslinger.protocol.SignalSlingerFirmwareSupport

interface DeviceTransport {
    fun connect()

    fun disconnect()

    fun sendCommands(commands: List<String>)

    fun readAvailableLines(): List<String>
}

object SignalSlingerReadPlan {
    val defaultLoadCommands: List<String> = SignalSlingerFirmwareSupport.resolve(null).fullLoadCommands
}
