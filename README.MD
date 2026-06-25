# Event Dispatcher — Usage Guide

A coroutine-based event dispatcher for Spring applications built on Kotlin and kotlinx.coroutines.

## Installation

**Requirements:** Spring Boot (WebFlux), Kotlin, Java 17+

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.kostack:event-dispatcher:1.0.0")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'io.github.kostack:event-dispatcher:1.0.0'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.kostack</groupId>
    <artifactId>event-dispatcher</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Core Concepts

| Type | Role |
|------|------|
| `AppEvent` | Marker interface for all events |
| `SuspendEventListener<T>` | Interface for event handlers |
| `SuspendDispatcher` | Spring component that routes events to listeners |

---

## 1. Define an Event

Implement `AppEvent` — a data class is the typical choice:

```kotlin
data class UserRegisteredEvent(val userId: String, val email: String) : AppEvent
```

---

## 2. Implement a Listener

Implement `SuspendEventListener<T>` and register it as a Spring bean:

```kotlin
@Component
class UserRegisteredListener : SuspendEventListener<UserRegisteredEvent> {
    override val eventType = UserRegisteredEvent::class
    // optional — default is 0; higher value = higher priority
    override val priority = 10
    // optional — default is 30 seconds; listener is cancelled if exceeded
    override val timeout = 5.seconds

    override suspend fun on(event: UserRegisteredEvent) {
        println("New user registered: ${event.email}")
    }
}
```

### Listener properties

| Property | Default | Description |
|----------|---------|-------------|
| `eventType` | required | The `KClass` of the event this listener handles |
| `priority` | `0` | Higher value = invoked first in `publishSequential` |
| `timeout` | `30.seconds` | Max duration before the listener is cancelled |

---

## 3. Publish Events

Inject `SuspendDispatcher` and choose a publish strategy.

### `publishSequential` — ordered, one at a time

Listeners are invoked one after another in descending priority order. Each listener finishes before the next starts.

```kotlin
@Service
class UserService(private val dispatcher: SuspendDispatcher) {

    suspend fun register(userId: String, email: String) {
        // ... create user ...
        dispatcher.publishSequential(UserRegisteredEvent(userId, email))
    }
}
```

### `publishParallel` — concurrent, caller waits for all

All matching listeners run concurrently inside a `coroutineScope`. The call suspends until every listener completes.

```kotlin
suspend fun notifyAll(event: UserRegisteredEvent) {
    dispatcher.publishParallel(event)
    // all listeners have finished by this point
}
```

### `publishAsync` — fire-and-forget, returns jobs

Listeners are launched in the background. Returns a `List<Job>` so the caller can choose whether to wait or not.

```kotlin
// Fire and forget — don't wait
dispatcher.publishAsync(UserRegisteredEvent(userId, email))

// Or wait for specific jobs
val jobs = dispatcher.publishAsync(event, scope = this)
jobs.joinAll()
```

---

## 4. Priority Ordering (`publishSequential`)

Listeners with a higher `priority` value are invoked first:

```kotlin
val first = object : SuspendEventListener<UserRegisteredEvent> {
    override val eventType = UserRegisteredEvent::class
    override val priority = 20          // invoked 1st
    override suspend fun on(event: UserRegisteredEvent) { println("first") }
}

val second = object : SuspendEventListener<UserRegisteredEvent> {
    override val eventType = UserRegisteredEvent::class
    override val priority = 1           // invoked 2nd
    override suspend fun on(event: UserRegisteredEvent) { println("second") }
}

val dispatcher = SuspendDispatcher(listOf(second, first))
dispatcher.publishSequential(UserRegisteredEvent("1", "a@b.com"))
// output: first, then second
```

> Priority has no effect on `publishParallel` or `publishAsync` — both launch all listeners concurrently.

---

## 5. Error Isolation

A failing listener does **not** propagate the exception to the caller or stop other listeners:

```kotlin
val fragile = object : SuspendEventListener<UserRegisteredEvent> {
    override val eventType = UserRegisteredEvent::class
    override suspend fun on(event: UserRegisteredEvent) {
        throw RuntimeException("something went wrong")
    }
}

// No exception thrown here — error is logged, execution continues
dispatcher.publishSequential(UserRegisteredEvent("1", "a@b.com"))
```

---

## 6. Timeout Handling

Set `timeout` on a listener to limit how long it may run. If exceeded, the listener is cancelled and an error is logged:

```kotlin
val slowListener = object : SuspendEventListener<UserRegisteredEvent> {
    override val eventType = UserRegisteredEvent::class
    override val timeout = 500.milliseconds

    override suspend fun on(event: UserRegisteredEvent) {
        delay(10_000) // will be cancelled after 500ms
    }
}
```

---

## 7. Non-Matching Events

If no listener is registered for an event type, the dispatcher logs a warning and does nothing:

```kotlin
data class UnhandledEvent(val info: String) : AppEvent

// Logs: WARN — No listeners registered for event: UnhandledEvent
dispatcher.publishSequential(UnhandledEvent("ignored"))
```

---

## Publish Strategy Comparison

| Strategy | Order guaranteed | Caller waits | Use when |
|----------|-----------------|--------------|----------|
| `publishSequential` | Yes (by priority) | Yes | Side effects must not overlap |
| `publishParallel` | No | Yes | Independent listeners, speed matters |
| `publishAsync` | No | No (opt-in via jobs) | Fire-and-forget background work |
