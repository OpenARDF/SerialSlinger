package com.openardf.serialslinger.platform

internal actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

internal actual fun platformSleep(milliseconds: Long) {
    Thread.sleep(milliseconds)
}
