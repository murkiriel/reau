package com.reau.app

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The notifications and the location granted before the screen opens (put it before the compose rule, `@get:Rule(order = 0)`):
 * a @Before runs after the rule has launched MainActivity, and with the dash's name unknown the screen asks for the location at once,
 * so Android's window covered it ("never becomes RESUMED", "no compose hierarchies") in the first test classes of a run, before any
 * other test had granted it.
 */
class GrantedBeforeLaunch : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.packageName
            val asked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS else PERMISSIONS
            for (permission in asked) instrumentation.uiAutomation.grantRuntimePermission(app, permission)
            base.evaluate()
        }
    }

    private companion object {
        val PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
