package io.github.kostack.event_dispatcher

import kotlin.time.Duration

open class ListenerException(
  val listenerName: String,
  message: String,
  cause: Throwable? = null
) : RuntimeException(message, cause) {
  constructor(
    listenerName: String,
    cause: Throwable
  ) : this(listenerName, "Listener $listenerName failed: $cause", cause)
}

class ListenerTimeoutException(
  listenerName: String,
  val timeout: Duration
) : ListenerException(listenerName, "Listener $listenerName timed out after $timeout")
