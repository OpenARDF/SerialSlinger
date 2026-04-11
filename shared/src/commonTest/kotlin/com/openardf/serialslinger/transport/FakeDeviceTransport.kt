package com.openardf.serialslinger.transport

class FakeDeviceTransport(
    private val scriptedResponses: Map<String, List<String>> = emptyMap(),
) : DeviceTransport {
    var isConnected: Boolean = false
        private set

    val sentCommands: MutableList<String> = mutableListOf()

    private val pendingLines: MutableList<String> = mutableListOf()

    override fun connect() {
        isConnected = true
    }

    override fun disconnect() {
        isConnected = false
    }

    override fun sendCommands(commands: List<String>) {
        sentCommands += commands
        commands.forEach { command ->
            pendingLines += scriptedResponses[command].orEmpty()
        }
    }

    override fun readAvailableLines(): List<String> {
        val lines = pendingLines.toList()
        pendingLines.clear()
        return lines
    }
}
