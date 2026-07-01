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
| `timeout` | `30000` (ms) | Max duration in milliseconds before the listener is cancelled |

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

All matching listeners run concurrently inside a `coroutineScope`. The call suspends until every listener completes.

```kotlin
suspend fun notifyAll(event: UserRegisteredEvent) {
    dispatcher.publishParallel("user.registered", event)
    // all listeners have finished by this point
}
```

### `publishAsync` — fire-and-forget, returns jobs

Listeners are launched in the background. Returns a `List<Job>` so the caller can choose whether to wait or not.

```kotlin
// Fire and forget — don't wait
dispatcher.publishAsync("user.registered", UserRegisteredEvent(userId, email))

// Or wait for specific jobs
val jobs = dispatcher.publishAsync("user.registered", event, scope = this)
jobs.joinAll()
```

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

## 5. Error Isolation

A failing listener does **not** propagate the exception to the caller or stop other listeners:

```kotlin
@Component
class FragileListener {

    @SuspendListener(name = "user.registered")
    suspend fun on(event: UserRegisteredEvent) {
        throw RuntimeException("something went wrong")
    }
}

// No exception thrown here — error is logged, execution continues
dispatcher.publishSequential("user.registered", UserRegisteredEvent("1", "a@b.com"))
```

---

## 6. Timeout Handling

Set `timeout` (in milliseconds) on a listener to limit how long it may run. If exceeded, the listener is cancelled and an error is logged:

```kotlin
@Component
class SlowListener {

    @SuspendListener(name = "user.registered", timeout = 500) // 500ms
    suspend fun on(event: UserRegisteredEvent) {
        delay(10_000) // will be cancelled after 500ms
    }
}
```

---

## 7. Non-Matching Events

If no listener is registered for a given name, the dispatcher logs a warning and does nothing:

```kotlin
// Logs: WARN — No listeners registered for name: unhandled
dispatcher.publishSequential("unhandled", SomeEvent())
```

---

## Publish Strategy Comparison

| Strategy | Order guaranteed | Caller waits | Use when |
|----------|-----------------|--------------|----------|
| `publishSequential` | Yes (by priority) | Yes | Side effects must not overlap |
| `publishParallel` | No | Yes | Independent listeners, speed matters |
| `publishAsync` | No | No (opt-in via jobs) | Fire-and-forget background work |

---

## License

MIT — see [LICENSE](LICENSE).
