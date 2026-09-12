package com.winlator.cmod.feature.stores.steam.events
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

sealed interface Event<T>

class EventDispatcher {
    // Use ConcurrentHashMap and CopyOnWriteArrayList for thread safety across Main and IO threads
    val listeners = ConcurrentHashMap<KClass<out Event<*>>, MutableList<Pair<String, EventListener<Event<*>, *>>>>()

    open class EventListener<E : Event<T>, T>(
        val listener: (E) -> T,
        val once: Boolean = false,
    )

    interface JavaEventListener {
        fun onEvent(event: Any)
    }

    inline fun <reified E : Event<T>, T> on(noinline listener: (E) -> T) {
        addListener<E, T>(listener, false)
    }

    inline fun <reified E : Event<T>, T> once(noinline listener: (E) -> T) {
        addListener<E, T>(listener, true)
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : Event<T>, T> addListener(
        noinline listener: (E) -> T,
        once: Boolean,
    ) {
        val eventClass = E::class
        val typedListener =
            Pair(
                listener.toString(),
                EventListener<Event<T>, T>({ event ->
                    listener(event as E)
                }, once),
            )
        // computeIfAbsent is atomic in ConcurrentHashMap
        listeners.computeIfAbsent(eventClass) { CopyOnWriteArrayList() }
            .add(typedListener as Pair<String, EventListener<Event<*>, *>>)
    }

    inline fun <reified E : Event<T>, T> off(noinline listener: (E) -> T) {
        val eventClass = E::class
        listeners[eventClass]?.removeIf {
            it.first == listener.toString()
        }
    }

    inline fun <reified E : Event<*>> clearAllListenersOf() {
        val targetClass = E::class
        // Correctly identify keys that are subclasses of the target event class
        listeners.keys.removeIf { key ->
            targetClass.java.isAssignableFrom(key.java)
        }
    }

    fun clearAllListeners() {
        listeners.clear()
    }

    @Suppress("UNCHECKED_CAST")
    fun onJava(
        eventClass: KClass<out Event<*>>,
        listener: JavaEventListener,
    ) {
        val eventListener =
            EventListener<Event<Any?>, Any?>({ event ->
                listener.onEvent(event!!)
                null
            }, false)
        val typedListener = Pair(listener.toString(), eventListener as EventListener<Event<*>, *>)
        listeners.computeIfAbsent(eventClass) { CopyOnWriteArrayList() }
            .add(typedListener)
    }

    fun offJava(
        eventClass: KClass<out Event<*>>,
        listener: JavaEventListener,
    ) {
        listeners[eventClass]?.removeIf {
            it.first == listener.toString()
        }
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : Event<T>, reified T> emit(
        event: E,
        noinline resultAggregator: ((Array<T>) -> T)? = null,
    ): T? {
        val eventClass = E::class
        return listeners[eventClass]?.let { eventListeners ->
            // CopyOnWriteArrayList iterator is safe for concurrent modification
            val results =
                eventListeners
                    .map { eventListener ->
                        val result = eventListener.second.listener(event)
                        if (result == null && Unit is T) Unit as T else result as T
                    }.toTypedArray()
            eventListeners.removeIf { it.second.once }
            resultAggregator?.let { it(results) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun emitJava(event: Event<*>): Any? {
        val eventClass = event::class
        return listeners[eventClass]?.let { eventListeners ->
            val results =
                eventListeners
                    .map { eventListener ->
                        eventListener.second.listener(event)
                    }.toTypedArray()
            eventListeners.removeIf { it.second.once }
            results.firstOrNull()
        }
    }
}
