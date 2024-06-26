package org.tasks.time

import org.tasks.data.BuildConfig
import java.util.Date

object DateTimeUtils2 {
    @JvmStatic
    fun currentTimeMillis(): Long {
        return MILLIS_PROVIDER.millis
    }

    private val SYSTEM_MILLIS_PROVIDER = SystemMillisProvider()

    @Volatile
    private var MILLIS_PROVIDER: MillisProvider = SYSTEM_MILLIS_PROVIDER

    fun setCurrentMillisFixed(millis: Long) {
        MILLIS_PROVIDER = FixedMillisProvider(millis)
    }

    fun setCurrentMillisSystem() {
        MILLIS_PROVIDER = SYSTEM_MILLIS_PROVIDER
    }
}

fun printTimestamp(timestamp: Long): String =
    if (BuildConfig.DEBUG) Date(timestamp).toString() else timestamp.toString()