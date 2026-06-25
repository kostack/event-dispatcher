package io.github.kostack.event_dispatcher

import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface SuspendEventListener<T : AppEvent> {
  suspend fun on(event: T)

  val eventType: KClass<T>
  val priority: Int get() = 0
  val timeout: Duration get() = 30.seconds
}
