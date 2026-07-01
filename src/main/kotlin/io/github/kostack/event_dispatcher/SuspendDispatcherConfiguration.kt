package io.github.kostack.event_dispatcher

import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SuspendDispatcherConfiguration(private val applicationContext: ApplicationContext) {
  @Bean
  fun suspendDispatcher(): SuspendDispatcher {
    val beans =
      applicationContext.beanDefinitionNames
        .filter { name -> hasAnySuspendListenerMethod(applicationContext.getType(name) ?: return@filter false) }
        .mapNotNull { name ->
          try {
            applicationContext.getBean(name)
          } catch (_: Exception) {
            null
          }
        }
    return SuspendDispatcher(beans)
  }

  private fun hasAnySuspendListenerMethod(clazz: Class<*>): Boolean {
    var current: Class<*>? = clazz
    while (current != null && current != Any::class.java) {
      if (current.declaredMethods.any { it.isAnnotationPresent(SuspendListener::class.java) }) return true
      current = current.superclass
    }
    return false
  }
}
