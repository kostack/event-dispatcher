# Event Dispatcher

A coroutine-based event dispatcher for Spring Boot applications written in Kotlin:

- **Annotation-based listeners**: mark a bean method with `@SuspendListener` and it is discovered automatically
- **Three publish strategies**: sequential by priority, parallel, or fire-and-forget
- **Per-listener timeouts**
- **Predictable failures**: listener errors reach the caller as a `ListenerException` naming the listener

## Requirements

- Java 25
- Spring Boot 4 (servlet or reactive)
- Kotlin coroutines (`kotlinx-coroutines-core`)
- Kotlin reflection (`kotlin-reflect`)

## Installation

The library does not bring Spring, coroutines or Kotlin reflection with it: your application provides them, at the
versions its own Spring Boot manages. Add the library next to them.

### Gradle Kotlin DSL

```kotlin
dependencies {
  implementation("org.springframework.boot:spring-boot-starter")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("io.github.kostack:event-dispatcher:<version>")
}
```

### Gradle Groovy DSL

```groovy
dependencies {
  implementation 'org.springframework.boot:spring-boot-starter'
  implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core'
  implementation 'org.jetbrains.kotlin:kotlin-reflect'
  implementation 'io.github.kostack:event-dispatcher:<version>'
}
```

### Maven

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>org.jetbrains.kotlinx</groupId>
  <artifactId>kotlinx-coroutines-core</artifactId>
</dependency>
<dependency>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-reflect</artifactId>
</dependency>
<dependency>
  <groupId>io.github.kostack</groupId>
  <artifactId>event-dispatcher</artifactId>
  <version>VERSION</version>
</dependency>
```

> Any Spring Boot starter (`spring-boot-starter-web`, `spring-boot-starter-webflux`, …) includes
> `spring-boot-starter`, and most Spring Boot Kotlin projects already have `kotlin-reflect`. If one of these is
> missing, the build still succeeds but the application fails at runtime with `NoClassDefFoundError`.

---

## Core Concepts

| Type | Role |
|------|------|
| `AppEvent` | Marker interface for all events |
| `@SuspendListener` | Marks a bean method as a listener for an event name |
| `SuspendDispatcher` | Routes events to listeners by name; registered as a bean by auto-configuration |
| `ListenerException` | Thrown when a listener fails; names the listener and wraps the original exception |
| `ListenerTimeoutException` | `ListenerException` thrown when a listener exceeds its timeout |

---

## 1. Define an Event

Implement `AppEvent`; a data class is the typical choice:

```kotlin
data class UserRegisteredEvent(val userId: String, val email: String) : AppEvent
```

---

## 2. Implement a Listener

Annotate a method that takes the event as its only parameter, and register the class as a Spring bean. The method is
usually `suspend`, but plain functions work too:

```kotlin
@Component
class UserRegisteredListener {
  @SuspendListener(name = "user.registered", priority = 10)
  suspend fun on(event: UserRegisteredEvent) {
    println("New user registered: ${event.email}")
  }
}
```

At startup, the auto-configuration finds every Spring bean with a `@SuspendListener` method and registers it with the
`SuspendDispatcher` bean. No manual wiring is needed. To replace the dispatcher, declare your own `SuspendDispatcher`
bean.

The annotation can also sit on a method of a superclass or an interface that the bean overrides; the override inherits
it:

```kotlin
interface UserEvents {
  @SuspendListener(name = "user.registered")
  suspend fun onRegistered(event: UserRegisteredEvent)
}

@Component
class UserEventsHandler : UserEvents {
  override suspend fun onRegistered(event: UserRegisteredEvent) {
    // ...
  }
}
```

Listeners are matched by `name` only, not by event type. Every listener for a name must accept every event published
under that name; a listener whose parameter type does not match fails with a `ListenerException`.

### `@SuspendListener` parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `name` | `""` | The event name this listener handles; matched against the name passed to the publish methods |
| `priority` | `0` | Higher value = invoked first in `publishSequential` |
| `timeout` | `30000` | Maximum duration, in `timeUnit`, before the listener is cancelled |
| `timeUnit` | `TimeUnit.MILLISECONDS` | Unit of `timeout` |

---

## 3. Publish Events

Inject `SuspendDispatcher` and choose a publish strategy. All publish methods take a `name` that selects which listeners
to invoke. Publishing a name with no listeners does nothing (it is logged at `INFO`).

### `publishSequential`: ordered, one at a time

Listeners are invoked one after another, in descending priority order. Each listener finishes before the next starts.

```kotlin
@Service
class UserService(private val dispatcher: SuspendDispatcher) {
  suspend fun register(userId: String, email: String) {
    // ... create user ...
    dispatcher.publishSequential("user.registered", UserRegisteredEvent(userId, email))
  }
}
```

### `publishParallel`: concurrent, caller waits for all

All matching listeners run concurrently. The call suspends until every listener has completed, or until the first
failure (see [Error Handling](#5-error-handling)).

By default, listeners run on the dispatcher's own scope (`Dispatchers.IO`, cancelled when the application shuts down).
Pass `scope` to run them in your own scope instead, for example to use a different dispatcher or to tie them to a
request's lifecycle. Cancelling that scope cancels the listeners, but a failing listener never cancels the scope.

```kotlin
suspend fun notifyAll(event: UserRegisteredEvent) {
  dispatcher.publishParallel("user.registered", event)
  // all listeners have finished by this point
}

// Or run the listeners in the caller's scope
suspend fun notifyAllInCallerScope(event: UserRegisteredEvent) =
  coroutineScope {
    dispatcher.publishParallel("user.registered", event, scope = this)
  }
```

### `publishAsync`: fire-and-forget, returns jobs

Listeners are launched in the background, and one `Job` per listener is returned so the caller can choose whether to
wait. `publishAsync` is not a `suspend` function, so it can be called from anywhere.

```kotlin
// Fire and forget on the dispatcher's own scope; don't wait
dispatcher.publishAsync("user.registered", UserRegisteredEvent(userId, email))

// Or launch in your own scope and wait for the jobs
suspend fun registerAndWait(event: UserRegisteredEvent) =
  coroutineScope {
    dispatcher.publishAsync("user.registered", event, scope = this).joinAll()
  }
```

When you pass your own scope, failures go to that scope's `CoroutineExceptionHandler`, or cancel the scope if it is not
a supervisor scope (as with `coroutineScope`). Use a `supervisorScope` if one listener's failure should not cancel the
others.

---

## 4. Priority Ordering (`publishSequential`)

Listeners with a higher `priority` value are invoked first:

```kotlin
@Component
class NotificationListeners {
  @SuspendListener(name = "user.registered", priority = 20) // invoked 1st
  suspend fun sendWelcomeEmail(event: UserRegisteredEvent) {
    println("first")
  }

  @SuspendListener(name = "user.registered", priority = 1) // invoked 2nd
  suspend fun auditLog(event: UserRegisteredEvent) {
    println("second")
  }
}
```

> Priority has no effect on `publishParallel` or `publishAsync`: both run all listeners concurrently.

---

## 5. Error Handling

A failing listener's exception is wrapped in a `ListenerException`, which names the listener (`listenerName`, for
example `UserRegisteredListener.on`) and keeps the original exception as its `cause`. The dispatcher does not log it, so
it is logged once, wherever you handle it.

| Strategy | Default (`stopOnFailure = true`) | `stopOnFailure = false` |
|----------|----------------------------------|-------------------------|
| `publishSequential` | Stops at the first failing listener and throws its exception | Runs every listener, then throws the first failure with later ones attached as suppressed exceptions |
| `publishParallel` | Throws the first failure after cancelling the remaining listeners and waiting for them to stop | Lets every listener finish, then throws the first failure (in listener order) with the others attached as suppressed exceptions |

`publishParallel` never cancels the scope you pass in, whichever mode you use.

`publishAsync` failures complete the listener's job exceptionally and go to the supplied scope's
`CoroutineExceptionHandler`. On the dispatcher's default scope, they are logged.

Cancellation always stops publishing and propagates unwrapped. JVM-fatal errors (`VirtualMachineError`, such as
`OutOfMemoryError`) are rethrown unwrapped too.

```kotlin
@Component
class FragileListener {
  @SuspendListener(name = "user.registered")
  suspend fun on(event: UserRegisteredEvent) {
    throw IllegalStateException("something went wrong")
  }
}

try {
  dispatcher.publishSequential("user.registered", UserRegisteredEvent("1", "a@b.com"))
} catch (ex: ListenerException) {
  // ex.listenerName == "FragileListener.on", ex.cause is the IllegalStateException
  log.error("Publishing user.registered failed", ex)
}
```

---

## 6. Timeout Handling

Set `timeout` (in `timeUnit`, milliseconds by default) on a listener to limit how long it may run. If it is exceeded,
the listener is cancelled and a `ListenerTimeoutException` (a subclass of `ListenerException`, with the configured
`timeout` as a `Duration`) propagates like any other failure. It is not a `CancellationException`, so callers and
`CoroutineExceptionHandler`s see it as a failure rather than as cancellation.

```kotlin
@Component
class SlowListeners {
  @SuspendListener(name = "user.registered", timeout = 500) // 500 ms
  suspend fun quick(event: UserRegisteredEvent) {
    delay(10_000) // cancelled after 500 ms
  }

  @SuspendListener(name = "user.registered", timeout = 2, timeUnit = TimeUnit.SECONDS)
  suspend fun slower(event: UserRegisteredEvent) {
    delay(10_000) // cancelled after 2 s
  }
}
```

A timeout from a `withTimeout` inside the listener is not a listener timeout: it surfaces as a plain `ListenerException`
whose cause is the `TimeoutCancellationException`.

---

## Publish Strategy Comparison

| Strategy | Order guaranteed | Caller waits | Use when |
|----------|-----------------|--------------|----------|
| `publishSequential` | Yes (by priority) | Yes | Side effects must not overlap |
| `publishParallel` | No | Yes, until all finish (or the first failure, by default) | Independent listeners, speed matters |
| `publishAsync` | No | No (opt-in via the returned jobs) | Fire-and-forget background work |

---

## License

MIT; see [LICENSE](LICENSE).
