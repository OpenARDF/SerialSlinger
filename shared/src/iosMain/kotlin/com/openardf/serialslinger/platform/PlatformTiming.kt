package com.openardf.serialslinger.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval
import platform.posix.usleep

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformCurrentTimeMillis(): Long =
    memScoped {
        val currentTime = alloc<timeval>()
        gettimeofday(currentTime.ptr, null)
        currentTime.tv_sec * 1000L + currentTime.tv_usec / 1000L
    }

internal actual fun platformSleep(milliseconds: Long) {
    usleep((milliseconds.coerceAtLeast(0L) * 1000L).toUInt())
}
