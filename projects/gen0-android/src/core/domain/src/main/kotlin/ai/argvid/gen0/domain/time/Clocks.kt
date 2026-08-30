package ai.argvid.gen0.domain.time

import java.time.Instant

fun interface MonotonicClock {
    fun nowUs(): Long
}

fun interface WallClock {
    fun now(): Instant
}
