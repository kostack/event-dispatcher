package io.github.kostack.event_dispatcher

import java.util.concurrent.TimeUnit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SuspendListener(
  val name: String = "",
  val priority: Int = 0,
  val timeout: Long = 30_000L,
  val timeUnit: TimeUnit = TimeUnit.MILLISECONDS
)
