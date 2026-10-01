@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidHeaderPresentationTest {
    @Test fun appVersionIsAlwaysPartOfTheHeaderTitle() {
        assertEquals("SerialSlinger 2.0.22a:", AndroidHeaderPresentation.appTitle("2.0.22a"))
    }
}
