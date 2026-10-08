package io.github.neareststep.nexusai.api;

import io.github.neareststep.nexusai.context.ContextRegistry;
import io.github.neareststep.nexusai.dialogue.DialogueService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.1.x signatures stay. New methods are additive. {@code API_VERSION} is 3.
 */
class ApiCompatibilityTest {

    @Test
    void versionIsThreeAndV2MethodsKeepTheirDescriptors() throws Exception {
        assertEquals(3, NexusAIApi.API_VERSION);
        assertMethod(NexusAIApi.class, "talk", CompletableFuture.class, Player.class, String.class, String.class);
        assertMethod(NexusAIApi.class, "registerContextProvider", void.class, Plugin.class, NexusContextProvider.class);
        assertMethod(NexusAIApi.class, "unregisterContextProvider", void.class, Plugin.class, String.class);
        assertMethod(NexusAIApi.class, "contextProviderIds", List.class);
        assertMethod(NexusAIApi.class, "bind", void.class, DialogueService.class);
        assertMethod(NexusAIApi.class, "bindContextRegistry", void.class, ContextRegistry.class);
        assertMethod(NexusAIApi.class, "isAvailable", boolean.class);
        assertMethod(NexusAIApi.class, "generate", CompletableFuture.class, Plugin.class, GenerationRequest.class);
        assertMethod(NexusAIApi.class, "registerPrompt", void.class, Plugin.class, String.class, PromptDefinition.class);
        assertMethod(NexusAIApi.class, "unregisterPrompt", boolean.class, Plugin.class, String.class);
        assertMethod(NexusAIApi.class, "registeredPromptIds", List.class, Plugin.class);
        assertMethod(NexusAIApi.class, "bindGeneration", void.class, io.github.neareststep.nexusai.generate.GenerationService.class);

        assertTrue(hasClassRetentionInternal(NexusAIApi.class, "bind"));
        assertTrue(hasClassRetentionInternal(NexusAIApi.class, "bindContextRegistry"));
        assertTrue(hasClassRetentionInternal(NexusAIApi.class, "bindGeneration"));
    }

    @Test
    void contextRequestPurposeIsUnchanged() {
        RecordComponent[] components = ContextRequest.class.getRecordComponents();
        assertEquals(List.of("playerId", "playerName", "world", "promptId", "purpose"),
                java.util.Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals(List.of("PLACEHOLDER", "TALK", "TALK_GREETING"),
                java.util.Arrays.stream(ContextRequest.Purpose.values()).map(Enum::name).toList());
    }

    @Test
    void contextProviderMethodsStay() throws Exception {
        assertMethod(NexusContextProvider.class, "id", String.class);
        assertMethod(NexusContextProvider.class, "priority", int.class);
        assertMethod(NexusContextProvider.class, "timeout", java.time.Duration.class);
        assertMethod(NexusContextProvider.class, "provide", CompletableFuture.class, ContextRequest.class);
    }

    private static void assertMethod(Class<?> type, String name, Class<?> returnType, Class<?>... params) throws Exception {
        Method method = type.getMethod(name, params);
        assertEquals(returnType, method.getReturnType(), name);
    }

    /**
     * {@code @ApiStatus.Internal} is retained in the class file, not at runtime, so reflection
     * cannot see it. This reads {@code RuntimeInvisibleAnnotations} instead.
     */
    private static boolean hasClassRetentionInternal(Class<?> type, String method) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource);
            }
            bytes = in.readAllBytes();
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        input.readUnsignedShort();
        input.readUnsignedShort();
        int count = input.readUnsignedShort();
        Map<Integer, String> utf8 = new HashMap<>();
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8.put(i, input.readUTF());
                case 7, 8, 16, 19, 20 -> input.readUnsignedShort();
                case 15 -> {
                    input.readUnsignedByte();
                    input.readUnsignedShort();
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt();
                case 5, 6 -> {
                    input.readLong();
                    i++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        input.readUnsignedShort();
        input.readUnsignedShort();
        input.readUnsignedShort();
        int interfaces = input.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) {
            input.readUnsignedShort();
        }
        skipMembers(input);
        int methods = input.readUnsignedShort();
        String descriptor = "Lorg/jetbrains/annotations/ApiStatus$Internal;";
        for (int i = 0; i < methods; i++) {
            input.readUnsignedShort();
            String name = utf8.get(input.readUnsignedShort());
            input.readUnsignedShort();
            int attributes = input.readUnsignedShort();
            boolean marked = false;
            for (int a = 0; a < attributes; a++) {
                String attribute = utf8.get(input.readUnsignedShort());
                int length = input.readInt();
                byte[] info = input.readNBytes(length);
                if (method.equals(name) && ("RuntimeInvisibleAnnotations".equals(attribute)
                        || "RuntimeVisibleAnnotations".equals(attribute))) {
                    marked = annotationPresent(info, utf8, descriptor);
                }
            }
            if (method.equals(name) && marked) {
                return true;
            }
        }
        return false;
    }

    private static void skipMembers(DataInputStream input) throws IOException {
        int members = input.readUnsignedShort();
        for (int i = 0; i < members; i++) {
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readUnsignedShort();
            int attributes = input.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                input.readUnsignedShort();
                int length = input.readInt();
                input.skipNBytes(length);
            }
        }
    }

    private static boolean annotationPresent(byte[] info, Map<Integer, String> utf8, String descriptor) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(info));
        int annotations = input.readUnsignedShort();
        for (int i = 0; i < annotations; i++) {
            String type = utf8.get(input.readUnsignedShort());
            int pairs = input.readUnsignedShort();
            for (int p = 0; p < pairs; p++) {
                input.readUnsignedShort();
                skipElementValue(input);
            }
            if (descriptor.equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static void skipElementValue(DataInputStream input) throws IOException {
        int tag = input.readUnsignedByte();
        switch (tag) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> input.readUnsignedShort();
            case 'e' -> {
                input.readUnsignedShort();
                input.readUnsignedShort();
            }
            case '@' -> {
                input.readUnsignedShort();
                int pairs = input.readUnsignedShort();
                for (int i = 0; i < pairs; i++) {
                    input.readUnsignedShort();
                    skipElementValue(input);
                }
            }
            case '[' -> {
                int values = input.readUnsignedShort();
                for (int i = 0; i < values; i++) {
                    skipElementValue(input);
                }
            }
            default -> throw new IOException("unknown element value tag " + tag);
        }
    }
}
