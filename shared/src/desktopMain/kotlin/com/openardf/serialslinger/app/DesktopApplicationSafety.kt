package com.openardf.serialslinger.app

import java.lang.reflect.Proxy

data class DesktopExitDecision(
    val mayExit: Boolean,
    val message: String?,
)

object DesktopExitProtection {
    fun decision(protectedOperation: String?): DesktopExitDecision {
        val operation = protectedOperation?.trim().orEmpty()
        if (operation.isBlank()) {
            return DesktopExitDecision(mayExit = true, message = null)
        }
        return DesktopExitDecision(
            mayExit = false,
            message =
                "$operation is still in progress.\n\n" +
                    "SerialSlinger must remain open until the operation completes or reports an error.",
        )
    }
}

object DesktopTerminationSignalSupport {
    fun install(onTerminationRequested: () -> Unit): Boolean {
        return runCatching {
            val signalClass = Class.forName("sun.misc.Signal")
            val signalHandlerClass = Class.forName("sun.misc.SignalHandler")
            val signal = signalClass.getConstructor(String::class.java).newInstance("TERM")
            val handler =
                Proxy.newProxyInstance(
                    signalHandlerClass.classLoader,
                    arrayOf(signalHandlerClass),
                ) { proxy, method, arguments ->
                    when (method.name) {
                        "handle" -> {
                            onTerminationRequested()
                            null
                        }
                        "toString" -> "SerialSlingerTerminationSignalHandler"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === arguments?.firstOrNull()
                        else -> null
                    }
                }
            signalClass.getMethod("handle", signalClass, signalHandlerClass).invoke(null, signal, handler)
            true
        }.getOrDefault(false)
    }
}
