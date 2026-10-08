package io.github.neareststep.nexusai.event;

import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.TokenUsage;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EventApiShapeTest {

    @Test
    void publicEventsHaveHandlerListsAndDoNotExposeTextSetters() throws Exception {
        Path dir = Path.of("src/main/java/io/github/neareststep/nexusai/api/event");
        assertTrue(Files.isDirectory(dir), dir.toString());
        List<Class<?>> concrete = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                String simple = file.getFileName().toString().replace(".java", "");
                Class<?> type = Class.forName("io.github.neareststep.nexusai.api.event." + simple);
                if (Event.class.isAssignableFrom(type) && !Modifier.isAbstract(type.getModifiers())) {
                    concrete.add(type);
                }
            }
        }
        assertTrue(concrete.size() >= 6, concrete.toString());
        for (Class<?> type : concrete) {
            Method list = type.getMethod("getHandlerList");
            assertTrue(Modifier.isStatic(list.getModifiers()), type.getName());
            Object event = newEvent(type);
            assertSame(list.invoke(null), type.getMethod("getHandlers").invoke(event), type.getName());
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class || method.getDeclaringClass() == Event.class) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String name = method.getName();
                boolean allowed = name.equals("setCancelled") || name.equals("setCancelReason");
                if (name.startsWith("set") && !allowed) {
                    fail(type.getSimpleName() + " exposes " + name);
                }
                if (method.getReturnType() == void.class && method.getParameterCount() > 0 && !allowed) {
                    fail(type.getSimpleName() + " exposes " + name);
                }
            }
        }
    }

    private static Object newEvent(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        Class<?>[] parameters = constructor.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i];
            if (parameter == boolean.class) {
                args[i] = false;
            } else if (parameter == int.class) {
                args[i] = 0;
            } else if (parameter == long.class) {
                args[i] = 1L;
            } else if (parameter == RequestOrigin.class) {
                args[i] = RequestOrigin.API;
            } else if (parameter == Duration.class) {
                args[i] = Duration.ZERO;
            } else if (parameter == TokenUsage.class) {
                args[i] = TokenUsage.none();
            } else if (parameter == NexusErrorKind.class) {
                args[i] = NexusErrorKind.PROVIDER_ERROR;
            }
        }
        Object event = constructor.newInstance(args);
        assertTrue(event instanceof Event);
        assertTrue(((Event) event).getHandlers() instanceof HandlerList);
        return event;
    }
}
