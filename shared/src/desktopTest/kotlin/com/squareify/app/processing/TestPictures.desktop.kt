package com.squareify.app.processing

import com.squareify.app.DesktopContext
import com.squareify.app.PlatformContext

actual fun testContext(): PlatformContext = DesktopContext
