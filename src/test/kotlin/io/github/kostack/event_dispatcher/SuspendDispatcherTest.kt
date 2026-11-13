package io.github.kostack.event_dispatcher

import io.github.kostack.event_dispatcher.event.DefaultEvent
import io.github.kostack.event_dispatcher.listener.DefaultListener
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

    val thrown = assertFailsWith<ListenerException> {
      SuspendDispatcher(listOf(failing)).publishSequential("failing", DefaultEvent("error-test"))
    }
    assertEquals(failure.toString(), thrown.cause.toString())
    assertTrue(thrown.listenerName.endsWith(".on"))
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
      val thrown = assertFailsWith<ListenerException> {
        dispatcher.publishParallel("failing", DefaultEvent("error-test"))
      }
      assertEquals(failure.toString(), thrown.cause.toString())
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
      assertIs<ListenerException>(reported)
      assertEquals(failure.toString(), reported?.cause.toString())
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

  private val slow =
    object {
      @SuspendListener(name = "slow", timeout = 50)
      suspend fun on(event: DefaultEvent) {
        delay(10_000.milliseconds)
      }
    }

  @Test
  fun `listener timeout propagates to sequential caller as ListenerTimeoutException`() = runTest {
    val thrown = assertFailsWith<ListenerTimeoutException> {
      SuspendDispatcher(listOf(slow)).publishSequential("slow", DefaultEvent("timeout-test"))
    }
    assertFalse(thrown is CancellationException)
    assertEquals(50.milliseconds, thrown.timeout)
    assertTrue(thrown.listenerName.endsWith(".on"))
  }

  @Test
  fun `listener timeout propagates to parallel caller as ListenerTimeoutException`() = runTest {
    val dispatcher = SuspendDispatcher(listOf(slow))
    try {
      assertFailsWith<ListenerTimeoutException> {
        dispatcher.publishParallel("slow", DefaultEvent("timeout-test"))
      }
    } finally {
      dispatcher.shutdown()
    }
  }

  @Test
  fun `async listener timeout reaches coroutine exception handler`() = runTest {
    var reported: Throwable? = null
    val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, ex -> reported = ex })
    try {
      val jobs = SuspendDispatcher(listOf(slow)).publishAsync("slow", DefaultEvent("timeout-test"), scope)
      jobs.joinAll()
      assertIs<ListenerTimeoutException>(reported)
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun `async failure on default scope does not reach uncaught exception handler`() = runTest {
    val failing = object {
      @SuspendListener(name = "failing")
      fun on(event: DefaultEvent): Unit = throw RuntimeException("boom")
    }
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    var uncaught: Throwable? = null
    Thread.setDefaultUncaughtExceptionHandler { _, ex -> uncaught = ex }
    val dispatcher = SuspendDispatcher(listOf(failing))
    try {
      val jobs = dispatcher.publishAsync("failing", DefaultEvent("error-test"))
      jobs.joinAll()
      assertTrue(jobs.single().isCancelled) // completed exceptionally
      assertNull(uncaught)
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous)
      dispatcher.shutdown()
    }
  }

  @Test
  fun `parallel failure cancels and awaits remaining listeners before throwing`() = runTest {
    val otherStarted = CompletableDeferred<Unit>()
    var otherCancelled = false
    var otherCompleted = false
    val failing = object {
      @SuspendListener(name = "mixed")
      suspend fun on(event: DefaultEvent) {
        otherStarted.await()
        throw RuntimeException("boom")
      }
    }
    val longRunning = object {
      @SuspendListener(name = "mixed")
      suspend fun on(event: DefaultEvent) {
        try {
          otherStarted.complete(Unit)
          awaitCancellation()
        } catch (ex: CancellationException) {
          otherCancelled = true
          throw ex
        } finally {
          otherCompleted = true
        }
      }
    }
    val dispatcher = SuspendDispatcher(listOf(failing, longRunning))
    try {
      assertFailsWith<RuntimeException> {
        dispatcher.publishParallel("mixed", DefaultEvent("mixed-test"))
      }
      // Both flags must already be set when publishParallel throws.
      assertTrue(otherCancelled)
      assertTrue(otherCompleted)
    } finally {
      dispatcher.shutdown()
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

  class Recorder(private val calls: MutableList<String>, private val tag: String, private val fail: Boolean) {
    @SuspendListener(name = "chain")
    fun on(event: DefaultEvent) {
      calls.add(tag)
      if (fail) throw RuntimeException(tag)
    }
  }

  @Test
  fun `parallel failure does not cancel a non-supervisor caller scope`() = runTest {
    val failing = object {
      @SuspendListener(name = "failing")
      fun on(event: DefaultEvent): Unit = throw RuntimeException("boom")
    }
    val dispatcher = SuspendDispatcher(listOf(failing))
    val scope = CoroutineScope(Job() + Dispatchers.Default)
    try {
      assertFailsWith<ListenerException> {
        dispatcher.publishParallel("failing", DefaultEvent("scope-test"), scope)
      }
      assertTrue(scope.isActive)
      // The internal supervisor job must be completed, not left behind as an active child.
      assertTrue(scope.coroutineContext.job.children.none())

      // Same within structured concurrency: catching the failure lets coroutineScope complete normally.
      val outcome = coroutineScope {
        try {
          dispatcher.publishParallel("failing", DefaultEvent("scope-test"), this)
          "no failure"
        } catch (ex: ListenerException) {
          "caught"
        }
      }
      assertEquals("caught", outcome)
    } finally {
      scope.cancel()
      dispatcher.shutdown()
    }
  }

  @Test
  fun `publishSequential stops at first failure by default`() = runTest {
    val calls = mutableListOf<String>()
    val dispatcher = SuspendDispatcher(listOf(Recorder(calls, "a", fail = true), Recorder(calls, "b", fail = false)))

    assertFailsWith<ListenerException> { dispatcher.publishSequential("chain", DefaultEvent("chain")) }

    assertEquals(1, calls.size)
  }

  @Test
  fun `publishSequential with stopOnFailure false runs all listeners and reports every failure`() = runTest {
    val calls = mutableListOf<String>()
    val dispatcher =
      SuspendDispatcher(
        listOf(
          Recorder(calls, "a", fail = true),
          Recorder(calls, "b", fail = false),
          Recorder(calls, "c", fail = true)
        )
      )

    val thrown = assertFailsWith<ListenerException> {
      dispatcher.publishSequential("chain", DefaultEvent("chain"), stopOnFailure = false)
    }

    assertEquals(listOf("a", "b", "c"), calls.sorted())
    assertEquals(1, thrown.suppressed.size)
    assertEquals(
      setOf("a", "c"),
      (listOf(thrown) + thrown.suppressed).map { it.cause?.message }.toSet()
    )
  }

  @Test
  fun `timeout inside a listener is a listener failure, not a listener timeout`() = runTest {
    val inner = object {
      @SuspendListener(name = "inner", timeout = 10_000)
      suspend fun on(event: DefaultEvent) {
        withTimeout(10.milliseconds) { delay(1_000.milliseconds) }
      }
    }

    val thrown = assertFailsWith<ListenerException> {
      SuspendDispatcher(listOf(inner)).publishSequential("inner", DefaultEvent("inner-timeout"))
    }

    assertFalse(thrown is ListenerTimeoutException)
    assertIs<kotlinx.coroutines.TimeoutCancellationException>(thrown.cause)
  }

  @Test
  fun `listener throwing an Error is wrapped with the listener name`() = runTest {
    val unfinished = object {
      @SuspendListener(name = "todo")
      fun on(event: DefaultEvent): Unit = TODO()
    }

    val thrown = assertFailsWith<ListenerException> {
      SuspendDispatcher(listOf(unfinished)).publishSequential("todo", DefaultEvent("todo"))
    }

    assertIs<NotImplementedError>(thrown.cause)
    assertTrue(thrown.listenerName.endsWith(".on"))
  }

  @Test
  fun `publishParallel with stopOnFailure false lets every listener finish and reports every failure`() = runTest {
    val completed = mutableListOf<String>()
    val slowSuccess = object {
      @SuspendListener(name = "independent")
      suspend fun on(event: DefaultEvent) {
        delay(100)
        completed.add("slow")
      }
    }
    val dispatcher = SuspendDispatcher(listOf(FailingAfter("a", 1), slowSuccess, FailingAfter("c", 50)))

    val thrown = assertFailsWith<ListenerException> {
      dispatcher.publishParallel("independent", DefaultEvent("independent"), this, stopOnFailure = false)
    }

    assertEquals(listOf("slow"), completed)
    assertEquals(1, thrown.suppressed.size)
    assertEquals(
      setOf("a", "c"),
      (listOf(thrown) + thrown.suppressed).map { it.cause?.message }.toSet()
    )
  }

  @Test
  fun `publishParallel fails fast by default`() = runTest {
    val completed = mutableListOf<String>()
    val failing = object {
      @SuspendListener(name = "independent")
      suspend fun on(event: DefaultEvent) {
        delay(1)
        throw RuntimeException("a")
      }
    }
    val slowSuccess = object {
      @SuspendListener(name = "independent")
      suspend fun on(event: DefaultEvent) {
        delay(100)
        completed.add("slow")
      }
    }

    assertFailsWith<ListenerException> {
      SuspendDispatcher(listOf(failing, slowSuccess)).publishParallel("independent", DefaultEvent("independent"), this)
    }

    assertTrue(completed.isEmpty())
  }

  @Test
  fun `timeout honours timeUnit`() = runTest {
    val slowInSeconds = object {
      @SuspendListener(name = "slow-seconds", timeout = 2, timeUnit = TimeUnit.SECONDS)
      suspend fun on(event: DefaultEvent) {
        delay(10.seconds)
      }
    }
    val fastEnough = object {
      @SuspendListener(name = "fast-seconds", timeout = 2, timeUnit = TimeUnit.SECONDS)
      suspend fun on(event: DefaultEvent) {
        delay(1.seconds) // would time out if 2 were read as milliseconds
      }
    }
    val dispatcher = SuspendDispatcher(listOf(slowInSeconds, fastEnough))

    dispatcher.publishSequential("fast-seconds", DefaultEvent("fast"))
    val thrown = assertFailsWith<ListenerTimeoutException> {
      dispatcher.publishSequential("slow-seconds", DefaultEvent("slow"))
    }
    assertEquals(2.seconds, thrown.timeout)
    assertTrue(thrown.message!!.endsWith("timed out after 2s"))
  }

  class FailingAfter(private val tag: String, private val delayMs: Long) {
    @SuspendListener(name = "independent")
    suspend fun on(event: DefaultEvent) {
      delay(delayMs)
      throw RuntimeException(tag)
    }
  }
}
