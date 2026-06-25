package io.github.kostack.event_dispatcher.listener

import io.github.kostack.event_dispatcher.SuspendEventListener
import io.github.kostack.event_dispatcher.event.DefaultEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DefaultListener : SuspendEventListener<DefaultEvent> {
  override val eventType = DefaultEvent::class
  override val priority = 10

  override suspend fun on(event: DefaultEvent) {
    log.info("received event: {}", event.data)
  }

  companion object {
    val log = LoggerFactory.getLogger(DefaultListener::class.java)
  }
}
