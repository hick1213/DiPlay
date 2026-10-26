package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class LatestMoveDispatcherTest {
    private class Queue : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.addLast(command) }
        fun drain() { while (!jobs.isEmpty()) jobs.removeFirst().run() }
    }

    @Test fun coalescesOnlyDragPositionsAndPreservesGestureBoundaries() {
        val queue = Queue()
        val out = mutableListOf<String>()
        val dispatcher = LatestMoveDispatcher<String>(queue, out::add)
        dispatcher.submit("down", false)
        dispatcher.submit("move1", true)
        dispatcher.submit("move2", true)
        dispatcher.submit("move3", true)
        dispatcher.submit("up", false)
        dispatcher.submit("next-down", false)
        dispatcher.submit("next-move", true)
        dispatcher.submit("next-up", false)
        assertEquals(6, queue.jobs.size)
        queue.drain()
        assertEquals(listOf("down", "move3", "up", "next-down", "next-move", "next-up"), out)
    }

    @Test fun movementAfterQueuedMoveHasDispatchedGetsNewSlot() {
        val queue = Queue()
        val out = mutableListOf<String>()
        val dispatcher = LatestMoveDispatcher<String>(queue, out::add)
        assertTrue(dispatcher.submit("down", false))
        dispatcher.submit("moveA", true)
        queue.drain()
        dispatcher.submit("moveB", true)
        dispatcher.submit("moveC", true)
        queue.drain()
        assertEquals(listOf("down", "moveA", "moveC"), out)
    }

    @Test fun cancelIsNeverCoalesced() {
        val queue = Queue()
        val out = mutableListOf<String>()
        val dispatcher = LatestMoveDispatcher<String>(queue, out::add)
        dispatcher.submit("down", false)
        dispatcher.submit("move", true)
        dispatcher.submit("cancel", false)
        queue.drain()
        assertEquals(listOf("down", "move", "cancel"), out)
    }
}
