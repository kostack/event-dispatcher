package io.github.kostack.event_dispatcher

import io.github.kostack.event_dispatcher.event.DefaultEvent
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Collections

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

  @Test
  fun `registers listeners annotated on the class, a superclass or an interface`() {
    ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(SuspendDispatcherConfiguration::class.java))
      .withUserConfiguration(HierarchyListenerConfiguration::class.java)
      .run { context ->
        val received = context.getBean(Received::class.java)
        runBlocking {
          context.getBean(SuspendDispatcher::class.java).publishSequential("hierarchy", DefaultEvent("hierarchy"))
        }
        assertThat(received.from).containsExactlyInAnyOrder("class", "superclass", "interface")
      }
  }

  class Received {
    val from: MutableList<String> = Collections.synchronizedList(mutableListOf())
  }

  class ClassListener(
    private val received: Received
  ) {
    @SuspendListener(name = "hierarchy")
    fun on(event: DefaultEvent) {
      received.from += "class"
    }
  }

  abstract class AnnotatedBase {
    @SuspendListener(name = "hierarchy")
    abstract fun on(event: DefaultEvent)
  }

  class SuperclassListener(
    private val received: Received
  ) : AnnotatedBase() {
    override fun on(event: DefaultEvent) {
      received.from += "superclass"
    }
  }

  interface AnnotatedContract {
    @SuspendListener(name = "hierarchy")
    fun on(event: DefaultEvent)
  }

  class InterfaceListener(
    private val received: Received
  ) : AnnotatedContract {
    override fun on(event: DefaultEvent) {
      received.from += "interface"
    }
  }

  @Configuration(proxyBeanMethods = false)
  class HierarchyListenerConfiguration {
    @Bean
    fun received() = Received()

    @Bean
    fun classListener(received: Received) = ClassListener(received)

    @Bean
    fun superclassListener(received: Received) = SuperclassListener(received)

    @Bean
    fun interfaceListener(received: Received) = InterfaceListener(received)
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
