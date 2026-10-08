package io.github.neareststep.nexusai.event;

import io.github.neareststep.nexusai.api.event.NexusActionEvent;
import io.github.neareststep.nexusai.api.event.NexusGenerateFailEvent;
import io.github.neareststep.nexusai.api.event.NexusModerationFlagEvent;
import io.github.neareststep.nexusai.api.event.NexusPostGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusPreGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusProviderErrorEvent;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Registers empty listeners so {@link EventDispatcher} sees a handler list, and records the events
 * the dispatcher actually delivers.
 */
public final class EventSupport {

    public final List<Event> events = new ArrayList<>();
    public final Plugin plugin;
    private final RegisteredListener registered;

    private EventSupport(Plugin plugin, RegisteredListener registered) {
        this.plugin = plugin;
        this.registered = registered;
    }

    public static EventSupport register(String pluginName) {
        Plugin plugin = plugin(pluginName);
        Listener listener = (Listener) plugin;
        EventExecutor executor = (ignored, event) -> {
        };
        RegisteredListener registered = new RegisteredListener(
                listener, executor, EventPriority.NORMAL, plugin, false);
        for (Class<?> type : List.of(
                NexusPreGenerateEvent.class,
                NexusPostGenerateEvent.class,
                NexusGenerateFailEvent.class,
                NexusProviderErrorEvent.class,
                NexusActionEvent.class,
                NexusModerationFlagEvent.class)) {
            handlerList(type).register(registered);
        }
        return new EventSupport(plugin, registered);
    }

    public void clear() {
        events.clear();
    }

    public void close() {
        HandlerList.unregisterAll(plugin);
        EventDispatcher.install(null);
    }

    private static HandlerList handlerList(Class<?> type) {
        try {
            return (HandlerList) type.getMethod("getHandlerList").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Plugin plugin(String name) {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class, Listener.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if ("isEnabled".equals(method.getName())) {
                        return true;
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger(name);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
    }
}
