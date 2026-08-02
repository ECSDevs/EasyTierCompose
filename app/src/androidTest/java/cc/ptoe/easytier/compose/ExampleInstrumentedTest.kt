package cc.ptoe.easytier.compose

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Test
    fun appPackageAndResourcesAreAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("cc.ptoe.easytier.compose", context.packageName)
        assertNotNull(context.getString(R.string.app_name))
    }
}
