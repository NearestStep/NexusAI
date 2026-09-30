package io.github.neareststep.nexusai.dialogue;

/**
 * Player-facing outcome of one talk call. {@code text} is the NPC line when {@link #code} is
 * {@link TalkCode#REPLY} or {@link TalkCode#STARTED}.
 */
public record TalkResult(TalkCode code, String characterId, String text, String error, int limit) {

    public static TalkResult of(TalkCode code, String characterId) {
        return new TalkResult(code, characterId, "", "", 0);
    }

    public static TalkResult text(TalkCode code, String characterId, String text) {
        return new TalkResult(code, characterId, text == null ? "" : text, "", 0);
    }

    public static TalkResult failed(String characterId, String error) {
        return new TalkResult(TalkCode.FAILED, characterId, "", error == null ? "" : error, 0);
    }
}
