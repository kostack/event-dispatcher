package io.github.kostack.event_dispatcher.listener

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.event_dispatcher.event.DefaultEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DefaultListener {
  @SuspendListener(name = "default", priority = 10)
  suspend fun on(event: DefaultEvent) {
    log.info("received event: {}", event.data)
  }

  companion object {
    val log = LoggerFactory.getLogger(DefaultListener::class.java)
  }
}
