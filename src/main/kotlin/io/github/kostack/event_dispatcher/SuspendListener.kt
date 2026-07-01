package io.github.kostack.event_dispatcher

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SuspendListener(
  val name: String = "",
  val priority: Int = 0,
  // milliseconds
  val timeout: Long = 30_000L
)
