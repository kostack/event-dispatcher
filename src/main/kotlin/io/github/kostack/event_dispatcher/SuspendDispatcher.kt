package io.github.kostack.event_dispatcher

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.time.Duration
import kotlin.time.toDuration
import kotlin.time.toDurationUnit

class SuspendDispatcher(
  beans: List<Any>
) {
  private val scope =
    CoroutineScope(
      SupervisorJob() +
        Dispatchers.IO +
        CoroutineExceptionHandler { _, ex ->
          log.error("Async listener failed", ex)
        }
    )

  private data class ListenerDescriptor(
    val bean: Any,
    val function: KFunction<*>,
    val listener: SuspendListener
  ) {
    val timeout: Duration = listener.timeout.toDuration(listener.timeUnit.toDurationUnit())
  }

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

  fun publishAsync(
    name: String,
    event: AppEvent,
    scope: CoroutineScope = this.scope
  ): List<Job> = matchingDescriptors(name).map { desc -> scope.launch { invoke(desc, event) } }

  suspend fun publishSequential(
    name: String,
    event: AppEvent,
    stopOnFailure: Boolean = true
  ) {
    val failures = mutableListOf<ListenerException>()
    for (desc in matchingDescriptors(name, sorted = true)) {
      try {
        invoke(desc, event)
      } catch (ex: ListenerException) {
        if (stopOnFailure) throw ex
        failures += ex
      }
    }
    failures.throwFirstIfAny()
  }

  suspend fun publishParallel(
    name: String,
    event: AppEvent,
    scope: CoroutineScope = this.scope,
    stopOnFailure: Boolean = true
  ) {
    val matching = matchingDescriptors(name)
    if (matching.isEmpty()) return
    val supervisor = SupervisorJob(scope.coroutineContext[Job])
    val listenerScope = CoroutineScope(scope.coroutineContext + supervisor)
    try {
      val deferred = matching.map { desc -> listenerScope.async { invoke(desc, event) } }
      try {
        if (stopOnFailure) {
          deferred.awaitAll()
        } else {
          deferred
            .mapNotNull { d ->
              try {
                d.await()
                null
              } catch (ex: ListenerException) {
                ex
              }
            }.throwFirstIfAny()
        }
      } catch (ex: Throwable) {
        deferred.forEach { it.cancel() }
        withContext(NonCancellable) { deferred.joinAll() }
        throw ex
      }
    } finally {
      supervisor.complete()
    }
  }

  @PreDestroy
  fun shutdown() {
    scope.cancel()
  }

  private suspend fun invoke(
    descriptor: ListenerDescriptor,
    event: AppEvent
  ) {
    val name = listenerName(descriptor)
    val completed =
      try {
        withTimeoutOrNull(descriptor.timeout) {
          try {
            descriptor.function.callSuspend(descriptor.bean, event)
          } catch (ex: InvocationTargetException) {
            throw ex.targetException
          }
          true
        }
      } catch (ex: TimeoutCancellationException) {
        throw ListenerException(name, ex)
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: VirtualMachineError) {
        throw ex
      } catch (ex: Throwable) {
        throw ListenerException(name, ex)
      }
    if (completed == null) throw ListenerTimeoutException(name, descriptor.timeout)
  }

  private fun listenerName(descriptor: ListenerDescriptor): String =
    "${descriptor.bean::class.simpleName ?: descriptor.bean.javaClass.name}.${descriptor.function.name}"

  private fun List<ListenerException>.throwFirstIfAny() {
    val first = firstOrNull() ?: return
    drop(1).forEach(first::addSuppressed)
    throw first
  }

  private fun matchingDescriptors(
    name: String,
    sorted: Boolean = false
  ): List<ListenerDescriptor> {
    val seq = descriptors.asSequence().filter { it.listener.name == name }
    val result = if (sorted) seq.sortedByDescending { it.listener.priority }.toList() else seq.toList()
    if (result.isEmpty()) log.info("No listeners registered for name: {}", name)
    return result
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(SuspendDispatcher::class.java)

    private fun findAnnotationInHierarchy(
      kClass: KClass<*>,
      functionName: String,
      paramCount: Int
    ): SuspendListener? =
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
