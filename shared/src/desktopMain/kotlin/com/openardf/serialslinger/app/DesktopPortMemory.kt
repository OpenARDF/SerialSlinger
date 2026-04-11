package com.openardf.serialslinger.app

import java.util.prefs.Preferences

interface DesktopPortMemory {
    fun loadLastWorkingPortPath(): String?

    fun saveLastWorkingPortPath(portPath: String)
}

object PreferencesDesktopPortMemory : DesktopPortMemory {
    private const val nodePath = "com/openardf/serialslinger"
    private const val keyLastWorkingPortPath = "lastWorkingPortPath"
    private val preferences: Preferences = Preferences.userRoot().node(nodePath)

    override fun loadLastWorkingPortPath(): String? {
        return preferences.get(keyLastWorkingPortPath, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun saveLastWorkingPortPath(portPath: String) {
        preferences.put(keyLastWorkingPortPath, portPath)
        preferences.flush()
    }
}
