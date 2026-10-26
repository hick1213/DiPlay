package com.shilapi.xcertplay.orchestration

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Serial touch input without a growing backlog of obsolete drag positions.
 * Down/up/cancel events are *always* delivered in order. A pending move can be
 * replaced only by a newer move before the next exact event; it cannot cross
 * an up/down boundary into a different gesture.
 */
internal class LatestMoveDispatcher<T>(
    private val executor: Executor,
    private val deliver: (T) -> Unit,
) {
    private class MoveSlot<T>(var latest: T)
    private val lock = Any()
    private var move: MoveSlot<T>? = null

    fun submit(item: T, isMove: Boolean): Boolean = synchronized(lock) {
        try {
            if (isMove) {
                val pending = move
                if (pending != null) {
                    pending.latest = item
                } else {
                    val slot = MoveSlot(item)
                    move = slot
                    executor.execute {
                        val value = synchronized(lock) {
                            if (move === slot) move = null
                            slot.latest
                        }
                        deliver(value)
                    }
                }
            } else {
                // The already enqueued move remains before this exact event,
                // but subsequent moves must get a new slot after it.
                move = null
                executor.execute { deliver(item) }
            }
            true
        } catch (_: RejectedExecutionException) {
            move = null
            false
        }
    }
}
