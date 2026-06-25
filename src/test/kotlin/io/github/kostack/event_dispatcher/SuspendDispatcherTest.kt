package io.github.kostack.event_dispatcher

import io.github.kostack.event_dispatcher.event.DefaultEvent
import io.github.kostack.event_dispatcher.listener.DefaultListener
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SuspendDispatcherTest {
  private val listener = spyk(DefaultListener())
  private val dispatcher = SuspendDispatcher(listOf(listener))

  @Test
  fun `publishAsync invokes DefaultListener for matching event`() =
    runTest {
      val event = DefaultEvent("async-test")

      val jobs = dispatcher.publishAsync(event, this)
      jobs.joinAll()

      coVerify(exactly = 1) { listener.on(event) }
    }

  @Test
  fun `publishSequential invokes DefaultListener for matching event`() =
    runTest {
      val event = DefaultEvent("sequential-test")

      dispatcher.publishSequential(event)

      coVerify(exactly = 1) { listener.on(event) }
    }

  @Test
  fun `publishParallel invokes DefaultListener for matching event`() =
    runTest {
      val event = DefaultEvent("parallel-test")

      dispatcher.publishParallel(event)

      coVerify(exactly = 1) { listener.on(event) }
    }

  @Test
  fun `no listener is invoked for non-matching event type`() =
    runTest {
      val unrelatedEvent = object : AppEvent {}

      dispatcher.publishSequential(unrelatedEvent)

      coVerify(exactly = 0) { listener.on(any()) }
    }

  @Test
  fun `publishSequential invokes listeners in descending priority order`() =
    runTest {
      val callOrder = mutableListOf<Int>()

      val low =
        object : SuspendEventListener<DefaultEvent> {
          override val eventType = DefaultEvent::class
          override val priority = 1

          override suspend fun on(event: DefaultEvent) {
            callOrder.add(1)
          }
        }

      val high =
        object : SuspendEventListener<DefaultEvent> {
          override val eventType = DefaultEvent::class
          override val priority = 20

          override suspend fun on(event: DefaultEvent) {
            callOrder.add(20)
          }
        }

      val dispatcher = SuspendDispatcher(listOf(low, high))
      dispatcher.publishSequential(DefaultEvent("priority-test"))

      assertEquals(listOf(20, 1), callOrder)
    }

  @Test
  fun `DefaultListener has priority 10`() {
    assertEquals(10, listener.priority)
  }

  @Test
  fun `DefaultListener handles DefaultEvent type`() {
    assertEquals(DefaultEvent::class, listener.eventType)
  }

  @Test
  fun `publishParallel invokes all matching listeners`() =
    runTest {
      val results = mutableListOf<String>()

      val first =
        object : SuspendEventListener<DefaultEvent> {
          override val eventType = DefaultEvent::class

          override suspend fun on(event: DefaultEvent) {
            results.add("first")
          }
        }

      val second =
        object : SuspendEventListener<DefaultEvent> {
          override val eventType = DefaultEvent::class

          override suspend fun on(event: DefaultEvent) {
            results.add("second")
          }
        }

      SuspendDispatcher(listOf(first, second)).publishParallel(DefaultEvent("parallel"))

      assertEquals(2, results.size)
      assertTrue(results.containsAll(listOf("first", "second")))
    }

  @Test
  fun `listener exception does not propagate to caller`() =
    runTest {
      val failing =
        object : SuspendEventListener<DefaultEvent> {
          override val eventType = DefaultEvent::class

          override suspend fun on(event: DefaultEvent): Unit = throw RuntimeException("boom")
        }

      SuspendDispatcher(listOf(failing)).publishSequential(DefaultEvent("error-test"))
    }

  @Test
  fun `listener timeout is handled gracefully`() =
    runTest {
      val slow =
        object : SuspendEventListener<DefaultEvent> {
          override val eventType = DefaultEvent::class
          override val timeout = 1.milliseconds

          override suspend fun on(event: DefaultEvent) {
            delay(1_000.milliseconds)
          }
        }

      SuspendDispatcher(listOf(slow)).publishSequential(DefaultEvent("timeout-test"))
    }

  @Test
  fun `publishAsync with multiple listeners returns a job per listener`() =
    runTest {
      val second = spyk(DefaultListener())
      val event = DefaultEvent("multi-async")

      val jobs = SuspendDispatcher(listOf(listener, second)).publishAsync(event, this)
      jobs.joinAll()

      assertEquals(2, jobs.size)
      coVerify(exactly = 1) { listener.on(event) }
      coVerify(exactly = 1) { second.on(event) }
    }
}
