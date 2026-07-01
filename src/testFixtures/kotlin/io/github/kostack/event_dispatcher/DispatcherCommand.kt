package io.github.kostack.event_dispatcher

import io.github.kostack.event_dispatcher.event.DefaultEvent
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class DispatcherCommand(private val suspendDispatcher: SuspendDispatcher) : ApplicationRunner {
  override fun run(args: ApplicationArguments): Unit = runBlocking {
    log.info("--- dispatch-async ---")
    suspendDispatcher.publishAsync("default", DefaultEvent("async payload"))

    log.info("--- dispatch-sequential ---")
    suspendDispatcher.publishSequential("default", DefaultEvent("sequential payload"))

    log.info("--- dispatch-parallel ---")
    suspendDispatcher.publishParallel("default", DefaultEvent("parallel payload"))
  }

  companion object {
    private val log = LoggerFactory.getLogger(DispatcherCommand::class.java)
  }
}
