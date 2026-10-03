package io.github.neareststep.nexusai.context;

/**
 * Inserts a wrapped context block. The header stays outside the markers so the model can see
 * which span is data. An empty block leaves the original text unchanged.
 */
public final class ContextBlock {

    public static final String HEADER = "Player context:\n";

    private ContextBlock() {
    }

    public static String appendUser(String prompt, String wrapped) {
        String base = prompt == null ? "" : prompt;
        if (wrapped == null || wrapped.isBlank()) {
            return base;
        }
        return base + "\n\n" + HEADER + wrapped;
    }

    /**
     * Places the block after the character sheet and before the format instruction.
     * {@code formatInstruction} is the exact instruction already appended to {@code system}.
     */
    public static String spliceSystem(String system, String formatInstruction, String wrapped) {
        String base = system == null ? "" : system;
        if (wrapped == null || wrapped.isBlank()) {
            return base;
        }
        String block = "\n\n" + HEADER + wrapped;
        if (formatInstruction == null || formatInstruction.isBlank()) {
            return base + block;
        }
        String marker = "\n\n" + formatInstruction;
        int at = base.lastIndexOf(marker);
        if (at < 0) {
            return base + block;
        }
        return base.substring(0, at) + block + base.substring(at);
    }
}
