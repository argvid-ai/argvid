package ai.argvid.gen0.gimbal

import ai.argvid.gen0.domain.time.MonotonicClock
import java.util.PriorityQueue

fun interface GimbalScheduler {
    fun at(deadlineUs: Long, action: () -> Unit)
}

class ManualGimbalScheduler(
    startUs: Long = 0,
) : GimbalScheduler, MonotonicClock {
    private data class ScheduledAction(
        val deadlineUs: Long,
        val order: Long,
        val action: () -> Unit,
    )

    private val actions = PriorityQueue<ScheduledAction>(compareBy(ScheduledAction::deadlineUs, ScheduledAction::order))
    private var currentUs = startUs
    private var nextOrder = 0L

    override fun nowUs(): Long = currentUs

    override fun at(deadlineUs: Long, action: () -> Unit) {
        require(deadlineUs >= currentUs)
        actions += ScheduledAction(deadlineUs, nextOrder++, action)
    }

    fun advanceBy(deltaUs: Long) {
        require(deltaUs >= 0)
        advanceTo(currentUs + deltaUs)
    }

    fun advanceTo(targetUs: Long) {
        require(targetUs >= currentUs)
        while (actions.peek()?.deadlineUs?.let { it <= targetUs } == true) {
            val scheduled = actions.remove()
            currentUs = scheduled.deadlineUs
            scheduled.action()
        }
        currentUs = targetUs
    }
}
