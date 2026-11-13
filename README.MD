# Event Dispatcher — Usage Guide

A coroutine-based event dispatcher for Spring applications built on Kotlin and kotlinx.coroutines.

## Installation

**Requirements:** Spring Boot (WebFlux), Kotlin, Java 23+

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.kostack:event-dispatcher:<version>")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'io.github.kostack:event-dispatcher:<version>'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.kostack</groupId>
    <artifactId>event-dispatcher</artifactId>
    <version>VERSION</version>
</dependency>
```

---

## Core Concepts

| Type | Role |
|------|------|
| `AppEvent` | Marker interface for all events |
| `@SuspendListener` | Annotation that marks a method as an event listener |
| `SuspendDispatcher` | Spring component that routes events to listeners by name |
| `SuspendDispatcherConfiguration` | Auto-configuration that wires all annotated beans |

---

## 1. Define an Event

Implement `AppEvent` — a data class is the typical choice:

```kotlin
data class UserRegisteredEvent(val userId: String, val email: String) : AppEvent
```

---

## 2. Implement a Listener

Annotate a `suspend` method with `@SuspendListener` and register the class as a Spring bean:

```kotlin
@Component
class UserRegisteredListener {

    @SuspendListener(name = "user.registered", priority = 10)
    suspend fun on(event: UserRegisteredEvent) {
        println("New user registered: ${event.email}")
    }
}
```

`SuspendDispatcherConfiguration` automatically discovers all Spring beans that have `@SuspendListener`-annotated methods — no manual wiring needed.

### `@SuspendListener` parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `name` | `""` | The event name this listener handles; matched against the name passed to publish methods |
| `priority` | `0` | Higher value = invoked first in `publishSequential` |
| `timeout` | `30000` | Max duration, in `timeUnit`, before the listener is cancelled |
| `timeUnit` | `TimeUnit.MILLISECONDS` | Unit of `timeout` |

---

## 3. Publish Events

Inject `SuspendDispatcher` and choose a publish strategy. All publish methods take a `name` that selects which listeners to invoke.

### `publishSequential` — ordered, one at a time

Listeners are invoked one after another in descending priority order. Each listener finishes before the next starts.

```kotlin
@Service
class UserService(private val dispatcher: SuspendDispatcher) {

    suspend fun register(userId: String, email: String) {
        // ... create user ...
        dispatcher.publishSequential("user.registered", UserRegisteredEvent(userId, email))
    }
}
```

### `publishParallel` — concurrent, caller waits for all

All matching listeners run concurrently. The call suspends until every listener has completed, or until the first failure (see [Error Handling](#5-error-handling)).

By default, listeners run on the dispatcher's own scope (`Dispatchers.IO`, cancelled when the application shuts down). Pass `scope` to run them in your own scope instead, for example to use a different dispatcher or to tie them to a request's lifecycle. Cancelling that scope cancels the listeners, but a failing listener never cancels the scope.

```kotlin
suspend fun notifyAll(event: UserRegisteredEvent) {
    dispatcher.publishParallel("user.registered", event)
    // all listeners have finished by this point
}

// Or run the listeners in the caller's scope
suspend fun notifyAllInCallerScope(event: UserRegisteredEvent) = coroutineScope {
    dispatcher.publishParallel("user.registered", event, scope = this)
}
```

### `publishAsync` — fire-and-forget, returns jobs

Listeners are launched in the background. Returns a `List<Job>` so the caller can choose whether to wait or not. `publishAsync` is not a `suspend` function, so it can be called from anywhere.

```kotlin
// Fire and forget on the dispatcher's own scope — don't wait
dispatcher.publishAsync("user.registered", UserRegisteredEvent(userId, email))

// Or launch in your own scope and wait for the jobs
suspend fun registerAndWait(event: UserRegisteredEvent) = coroutineScope {
    dispatcher.publishAsync("user.registered", event, scope = this).joinAll()
}
```

When you pass your own scope, failures go to that scope's `CoroutineExceptionHandler`, or cancel it if it is not a supervisor scope (as with `coroutineScope`). Use a `supervisorScope` if one listener's failure should not cancel the others.

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

    @SuspendListener(name = "user.registered", priority = 1)  // invoked 2nd
    suspend fun auditLog(event: UserRegisteredEvent) {
        println("second")
    }
}
```

> Priority has no effect on `publishParallel` or `publishAsync` — both launch all listeners concurrently.

---

## 5. Error Handling

A failing listener's exception is wrapped in a `ListenerException`, which names the listener (`listenerName`) and keeps the original exception as its `cause`. The dispatcher does not log it, so it is logged once, wherever you handle it.

- `publishSequential` stops at the first failing listener and throws. Pass `stopOnFailure = false` to run every listener anyway: the first failure is thrown, with any later ones attached as suppressed exceptions.
- `publishParallel` fails fast: it throws the first failure after cancelling the remaining listeners and waiting for them to finish. Pass `stopOnFailure = false` to let every listener finish instead; the first failure (in listener order) is then thrown, with any others attached as suppressed exceptions. Either way, a failure never cancels the scope you pass in.
- `publishAsync` failures complete the listener's job exceptionally and go to the supplied scope's `CoroutineExceptionHandler`. On the dispatcher's default scope they are logged.

JVM-fatal errors (`VirtualMachineError`, such as `OutOfMemoryError`) and cancellation are rethrown unwrapped.

```kotlin
@Component
class FragileListener {

    @SuspendListener(name = "user.registered")
    suspend fun on(event: UserRegisteredEvent) {
        throw RuntimeException("something went wrong")
    }
}

// Throws ListenerException(cause = RuntimeException("something went wrong"))
dispatcher.publishSequential("user.registered", UserRegisteredEvent("1", "a@b.com"))
```

---

## 6. Timeout Handling

Set `timeout` (in `timeUnit`, milliseconds by default) on a listener to limit how long it may run. If exceeded, the listener is cancelled and a `ListenerTimeoutException` (a subclass of `ListenerException`) propagates through the same publishing mechanism as any other failure. It is not a `CancellationException`, so callers and `CoroutineExceptionHandler`s see it as a failure rather than as cancellation:

```kotlin
@Component
class SlowListener {

    @SuspendListener(name = "user.registered", timeout = 500) // 500ms
    // or: @SuspendListener(name = "user.registered", timeout = 2, timeUnit = TimeUnit.SECONDS)
    suspend fun on(event: UserRegisteredEvent) {
        delay(10_000) // will be cancelled after 500ms
    }
}
```

A timeout from a `withTimeout` inside the listener is not a listener timeout: it surfaces as a plain `ListenerException` whose cause is the `TimeoutCancellationException`.

---

## Publish Strategy Comparison

| Strategy | Order guaranteed | Caller waits | Use when |
|----------|-----------------|--------------|----------|
| `publishSequential` | Yes (by priority) | Yes | Side effects must not overlap |
| `publishParallel` | No | Yes (until all finish, or the first failure by default) | Independent listeners, speed matters |
| `publishAsync` | No | No (opt-in via jobs) | Fire-and-forget background work |

---

## License

MIT — see [LICENSE](LICENSE).
