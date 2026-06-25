package io.github.kostack.event_dispatcher.event

import io.github.kostack.event_dispatcher.AppEvent

data class DefaultEvent(
  val data: String
) : AppEvent
