package io.github.kostack.event_dispatcher

import io.github.kostack.event_dispatcher.event.DefaultEvent
import io.github.kostack.event_dispatcher.listener.DefaultListener
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SuspendDispatcherTest {
  private val listener = spyk(DefaultListener())
  private val dispatcher = SuspendDispatcher(listOf(listener))

  @Test
  fun `publishAsync invokes DefaultListener for matching name`() = runTest {
    val event = DefaultEvent("async-test")

    val jobs = dispatcher.publishAsync("default", event, this)
    jobs.joinAll()

    coVerify(exactly = 1) { listener.on(event) }
  }

  @Test
  fun `publishSequential invokes DefaultListener for matching name`() = runTest {
    val event = DefaultEvent("sequential-test")

    dispatcher.publishSequential("default", event)

    coVerify(exactly = 1) { listener.on(event) }
  }

  @Test
  fun `publishParallel invokes DefaultListener for matching name`() = runTest {
    val event = DefaultEvent("parallel-test")

    dispatcher.publishParallel("default", event, this)

    coVerify(exactly = 1) { listener.on(event) }
  }

  @Test
  fun `no listener is invoked for non-matching name`() = runTest {
    dispatcher.publishSequential("unknown", DefaultEvent("unrelated"))

    coVerify(exactly = 0) { listener.on(any()) }
  }

  @Test
  fun `publishSequential invokes listeners in descending priority order`() = runTest {
    val callOrder = mutableListOf<Int>()

    val low =
      object {
        @SuspendListener(name = "ordered", priority = 1)
        fun on(event: DefaultEvent) {
          callOrder.add(1)
        }
      }

    val high =
      object {
        @SuspendListener(name = "ordered", priority = 20)
        fun on(event: DefaultEvent) {
          callOrder.add(20)
        }
      }

    val dispatcher = SuspendDispatcher(listOf(low, high))
    dispatcher.publishSequential("ordered", DefaultEvent("priority-test"))

    assertEquals(listOf(20, 1), callOrder)
  }

  @Test
  fun `DefaultListener has priority 10`() {
    val annotation =
      DefaultListener::class.memberFunctions
        .firstNotNullOf { it.findAnnotation<SuspendListener>() }
    assertEquals(10, annotation.priority)
  }

  @Test
  fun `DefaultListener has name default`() {
    val annotation =
      DefaultListener::class.memberFunctions
        .firstNotNullOf { it.findAnnotation<SuspendListener>() }
    assertEquals("default", annotation.name)
  }

  @Test
  fun `publishParallel invokes all matching listeners`() = runTest {
    val results = mutableListOf<String>()

    val first =
      object {
        @SuspendListener(name = "multi")
        fun on(event: DefaultEvent) {
          results.add("first")
        }
      }

    val second =
      object {
        @SuspendListener(name = "multi")
        fun on(event: DefaultEvent) {
          results.add("second")
        }
      }

    SuspendDispatcher(listOf(first, second)).publishParallel("multi", DefaultEvent("parallel"), this)

    assertEquals(2, results.size)
    assertTrue(results.containsAll(listOf("first", "second")))
  }

  @Test
  fun `listener exception propagates to sequential caller`() = runTest {
    val failure = RuntimeException("boom")
    val failing =
      object {
        @SuspendListener(name = "failing")
        fun on(event: DefaultEvent): Unit = throw failure
      }

    val thrown = assertFailsWith<RuntimeException> {
      SuspendDispatcher(listOf(failing)).publishSequential("failing", DefaultEvent("error-test"))
    }
    assertEquals(failure.message, thrown.message)
  }

  @Test
  fun `listener exception propagates to parallel caller`() = runTest {
    val failure = RuntimeException("boom")
    val failing = object {
      @SuspendListener(name = "failing")
      suspend fun on(event: DefaultEvent): Unit {
        delay(1)
        throw failure
      }
    }
    val dispatcher = SuspendDispatcher(listOf(failing))
    try {
      val thrown = assertFailsWith<RuntimeException> {
        dispatcher.publishParallel("failing", DefaultEvent("error-test"))
      }
      assertEquals(failure.message, thrown.message)
    } finally {
      dispatcher.shutdown()
    }
  }

  @Test
  fun `async listener exception reaches coroutine exception handler`() = runTest {
    val failure = RuntimeException("boom")
    var reported: Throwable? = null
    val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, ex -> reported = ex })
    val failing = object {
      @SuspendListener(name = "failing")
      fun on(event: DefaultEvent): Unit = throw failure
    }
    try {
      SuspendDispatcher(listOf(failing)).publishAsync("failing", DefaultEvent("error-test"), scope).joinAll()
      assertTrue(reported is RuntimeException)
      assertEquals(failure.message, reported?.message)
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun `listener cancellation propagates to caller`() = runTest {
    val cancellation = CancellationException("cancelled")
    val failing = object {
      @SuspendListener(name = "failing")
      fun on(event: DefaultEvent): Unit = throw cancellation
    }
    val thrown = assertFailsWith<CancellationException> {
      SuspendDispatcher(listOf(failing)).publishSequential("failing", DefaultEvent("error-test"))
    }
    assertEquals(cancellation.message, thrown.message)
  }

  @Test
  fun `listener timeout propagates to caller`() = runTest {
    val slow =
      object {
        @SuspendListener(name = "slow", timeout = 1) // 1ms
        suspend fun on(event: DefaultEvent) {
          delay(1_000.milliseconds)
        }
      }

    assertFailsWith<TimeoutCancellationException> {
      SuspendDispatcher(listOf(slow)).publishSequential("slow", DefaultEvent("timeout-test"))
    }
  }

  @Test
  fun `publishAsync with multiple listeners returns a job per listener`() = runTest {
    val second = spyk(DefaultListener())
    val event = DefaultEvent("multi-async")

    val jobs = SuspendDispatcher(listOf(listener, second)).publishAsync("default", event, this)
    jobs.joinAll()

    assertEquals(2, jobs.size)
    coVerify(exactly = 1) { listener.on(event) }
    coVerify(exactly = 1) { second.on(event) }
  }
}
