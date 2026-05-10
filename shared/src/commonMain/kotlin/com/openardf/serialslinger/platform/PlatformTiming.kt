package com.openardf.serialslinger.platform

internal expect fun platformCurrentTimeMillis(): Long

internal expect fun platformSleep(milliseconds: Long)
