package io.github.kostack.event_dispatcher

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.util.ReflectionUtils

@AutoConfiguration
internal class SuspendDispatcherConfiguration(
  private val applicationContext: ApplicationContext
) {
  @Bean
  @ConditionalOnMissingBean
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

  private fun hasAnySuspendListenerMethod(clazz: Class<*>): Boolean =
    (ReflectionUtils.getAllDeclaredMethods(clazz).asSequence() + clazz.methods.asSequence())
      .any { AnnotatedElementUtils.hasAnnotation(it, SuspendListener::class.java) }
}
