package com.squareify.app.processing

import androidx.test.platform.app.InstrumentationRegistry
import com.squareify.app.PlatformContext

actual fun testContext(): PlatformContext = InstrumentationRegistry.getInstrumentation().targetContext
