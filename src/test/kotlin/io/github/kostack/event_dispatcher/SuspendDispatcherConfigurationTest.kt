package io.github.kostack.event_dispatcher

import io.github.kostack.event_dispatcher.event.DefaultEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class SuspendDispatcherConfigurationTest {
  private val contextRunner =
    ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(SuspendDispatcherConfiguration::class.java))
      .withUserConfiguration(ListenerConfiguration::class.java)

  @Test
  fun `auto-configuration creates suspend dispatcher`() {
    contextRunner.run { context ->
      assertThat(context).hasSingleBean(SuspendDispatcher::class.java)
    }
  }

  @Test
  fun `auto-configuration backs off when suspend dispatcher bean exists`() {
    contextRunner
      .withUserConfiguration(CustomDispatcherConfiguration::class.java)
      .run { context ->
        assertThat(context).hasSingleBean(SuspendDispatcher::class.java)
        assertThat(context).getBean("customSuspendDispatcher")
      }
  }

  @Configuration(proxyBeanMethods = false)
  class ListenerConfiguration {
    @Bean
    fun listener() =
      object {
        @SuspendListener(name = "default")
        suspend fun on(event: DefaultEvent) {
        }
      }
  }

  @Configuration(proxyBeanMethods = false)
  class CustomDispatcherConfiguration {
    @Bean
    fun customSuspendDispatcher() = SuspendDispatcher(emptyList())
  }
}
