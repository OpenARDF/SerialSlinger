@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import android.content.pm.ActivityInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-device instrumentation for startup, lifecycle, metadata, and USB-host declarations. */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @Test
    fun startupShowsSafeOperatorActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val texts = activity.window.decorView.allTextViews()
                assertTrue(texts.any { it.text.toString().startsWith("SerialSlinger ${BuildConfig.VERSION_NAME}") })

                val settings = texts.filterIsInstance<Button>().single { it.text.toString() == "Settings" }
                val tools = texts.filterIsInstance<Button>().single { it.text.toString() == "Tools" }
                assertTrue("Hardware-free diagnostic tools must remain reachable", tools.isEnabled)

                // A physical-device run may begin disconnected, while USB discovery is in flight,
                // or with a supported transmitter already loaded. Every state must keep the
                // operator on a recognizable, safe surface.
                val labels = texts.map { it.text.toString() }
                val startupStatusIsVisible =
                    labels.any {
                        it in
                            setOf(
                                "Reload Device Data",
                                "Connecting to device...",
                                "Searching for device...",
                                "Reading data...",
                            )
                    } || labels.contains("Connected Device")
                assertTrue("Startup must expose a connection status or loaded-device summary", startupStatusIsVisible)
                assertTrue(
                    "Settings must stay locked until a loaded device summary is available",
                    !settings.isEnabled || labels.contains("Connected Device"),
                )
            }
        }
    }

    @Test
    fun operatorSurfaceSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                val texts = activity.window.decorView.allTextViews()
                assertTrue(texts.any { it.text.toString().startsWith("SerialSlinger ${BuildConfig.VERSION_NAME}") })
                assertTrue(texts.filterIsInstance<Button>().any { it.text.toString() == "Settings" })
                assertTrue(texts.filterIsInstance<Button>().single { it.text.toString() == "Tools" }.isEnabled)
            }
        }
    }

    @Test
    fun installedMetadataMatchesTheTestedBuild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val activityInfo =
            context.packageManager.getActivityInfo(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.component!!,
                0,
            )

        assertEquals("com.SerialSlinger.openardf", context.packageName)
        assertEquals(BuildConfig.VERSION_NAME, packageInfo.versionName)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activityInfo.screenOrientation)
        assertNotNull(context.applicationInfo.loadIcon(context.packageManager))
    }

    @Test
    fun usbHostCapabilityRemainsOptional() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val features =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS)
                .reqFeatures
                .orEmpty()
        val usbHost = features.single { it.name == PackageManager.FEATURE_USB_HOST }

        // USB is needed for live configuration, but keeping it optional preserves preview and
        // diagnostic access on Android devices that do not advertise host capability.
        assertEquals(0, usbHost.flags and FeatureInfo.FLAG_REQUIRED)
    }
}

private fun View.allTextViews(): List<TextView> =
    buildList {
        if (this@allTextViews is TextView) add(this@allTextViews)
        if (this@allTextViews is ViewGroup) {
            repeat(childCount) { index -> addAll(getChildAt(index).allTextViews()) }
        }
    }
