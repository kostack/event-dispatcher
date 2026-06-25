package io.github.kostack.event_dispatcher

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SuspendDispatcher(
  private val listeners: List<SuspendEventListener<out AppEvent>>
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun <T : AppEvent> publishAsync(
    event: T,
    scope: CoroutineScope = this.scope
  ): List<Job> =
    matchingListeners(event).map { listener ->
      scope.launch { safeInvoke(listener, event) }
    }

  suspend fun <T : AppEvent> publishSequential(event: T) {
    matchingListeners(event, sorted = true).forEach { listener ->
      safeInvoke(listener, event)
    }
  }

  suspend fun <T : AppEvent> publishParallel(event: T) =
    coroutineScope {
      matchingListeners(event)
        .map { listener -> async { safeInvoke(listener, event) } }
        .awaitAll()
    }

  @PreDestroy
  fun shutdown() {
    scope.cancel()
  }

  private suspend fun <T : AppEvent> safeInvoke(
    listener: SuspendEventListener<T>,
    event: T
  ) {
    try {
      withTimeout(listener.timeout) { listener.on(event) }
    } catch (_: TimeoutCancellationException) {
      log.error("Listener timed out after {}: {}", listener.timeout, listener::class.simpleName)
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      log.error("Listener failed: {}", listener::class.simpleName, ex)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T : AppEvent> matchingListeners(
    event: T,
    sorted: Boolean = false
  ): List<SuspendEventListener<T>> {
    val seq =
      listeners
        .asSequence()
        .filter { it.eventType == event::class }
        .map { it as SuspendEventListener<T> }
    val result = if (sorted) seq.sortedByDescending { it.priority }.toList() else seq.toList()
    if (result.isEmpty()) log.warn("No listeners registered for event: {}", event::class.simpleName)
    return result
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(SuspendDispatcher::class.java)
  }
}
