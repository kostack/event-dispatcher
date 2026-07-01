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
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.time.Duration.Companion.milliseconds

class SuspendDispatcher(beans: List<Any>) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private data class ListenerDescriptor(val bean: Any, val function: KFunction<*>, val listener: SuspendListener)

  private val descriptors: List<ListenerDescriptor> =
    beans.flatMap { bean ->
      bean::class.memberFunctions.mapNotNull { function ->
        val annotation =
          function.findAnnotation<SuspendListener>()
            ?: findAnnotationInHierarchy(bean::class, function.name, function.parameters.size)
            ?: return@mapNotNull null
        ListenerDescriptor(bean, function, annotation)
      }
    }

  fun publishAsync(name: String, event: AppEvent, scope: CoroutineScope = this.scope): List<Job> =
    matchingDescriptors(name).map { desc -> scope.launch { safeInvoke(desc, event) } }

  suspend fun publishSequential(name: String, event: AppEvent) {
    matchingDescriptors(name, sorted = true).forEach { desc -> safeInvoke(desc, event) }
  }

  suspend fun publishParallel(name: String, event: AppEvent) = coroutineScope {
    matchingDescriptors(name).map { desc -> async { safeInvoke(desc, event) } }.awaitAll()
  }

  @PreDestroy
  fun shutdown() {
    scope.cancel()
  }

  private suspend fun safeInvoke(descriptor: ListenerDescriptor, event: AppEvent) {
    try {
      withTimeout(descriptor.listener.timeout.milliseconds) { descriptor.function.callSuspend(descriptor.bean, event) }
    } catch (_: TimeoutCancellationException) {
      log.error("Listener timed out after {}ms: {}", descriptor.listener.timeout, descriptor.bean::class.simpleName)
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      log.error("Listener failed: {}", descriptor.bean::class.simpleName, ex)
    }
  }

  private fun matchingDescriptors(name: String, sorted: Boolean = false): List<ListenerDescriptor> {
    val seq = descriptors.asSequence().filter { it.listener.name == name }
    val result = if (sorted) seq.sortedByDescending { it.listener.priority }.toList() else seq.toList()
    if (result.isEmpty()) log.warn("No listeners registered for name: {}", name)
    return result
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(SuspendDispatcher::class.java)

    private fun findAnnotationInHierarchy(kClass: KClass<*>, functionName: String, paramCount: Int): SuspendListener? =
      kClass.supertypes
        .mapNotNull { it.classifier as? KClass<*> }
        .firstNotNullOfOrNull { supertype ->
          supertype.memberFunctions
            .find { it.name == functionName && it.parameters.size == paramCount }
            ?.findAnnotation<SuspendListener>()
            ?: findAnnotationInHierarchy(supertype, functionName, paramCount)
        }
  }
}
