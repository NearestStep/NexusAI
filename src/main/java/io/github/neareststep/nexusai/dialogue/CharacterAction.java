package io.github.neareststep.nexusai.dialogue;

/**
 * One admin-defined action. The model may choose the name. It cannot change the command.
 */
public final class CharacterAction {

    private final String name;
    private final String description;
    private final String command;
    private final boolean console;
    private final int cooldownSeconds;
    private final int dailyLimit;
    private final String permission;

    public CharacterAction(
            String name,
            String description,
            String command,
            boolean console,
            int cooldownSeconds,
            int dailyLimit,
            String permission
    ) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.command = command;
        this.console = console;
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.dailyLimit = Math.max(0, dailyLimit);
        this.permission = permission == null || permission.isBlank() ? null : permission;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String command() {
        return command;
    }

    public boolean console() {
        return console;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public int dailyLimit() {
        return dailyLimit;
    }

    /**
     * @return extra permission the player must have, or {@code null} when any player may trigger it
     */
    public String permission() {
        return permission;
    }
}
