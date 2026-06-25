package com.bytedance.zgx.pocketmind.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.pocketmind.rcperf.RcPerfSyntheticZvecMemory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZvecNativePerfDeviceTest {
    @Test
    fun zvecSearchFiftyThousandRecordsReportsDeviceTiming() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "zvec-native-50k-perf").apply {
            deleteRecursively()
            mkdirs()
        }

        val measurement = try {
            RcPerfSyntheticZvecMemory.measure(rootDir = root)
        } finally {
            root.deleteRecursively()
        }

        println(
            "zvecMemorySearch50kMs=${measurement.elapsedMs} " +
                "recordCount=${measurement.recordCount} hitCount=${measurement.hitCount}",
        )
        assertEquals(RcPerfSyntheticZvecMemory.DEFAULT_RECORD_COUNT, measurement.recordCount)
        assertTrue(measurement.hitCount > 0)
        assertTrue(measurement.elapsedMs > 0)
    }
}
