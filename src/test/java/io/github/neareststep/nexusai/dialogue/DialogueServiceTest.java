package io.github.neareststep.nexusai.dialogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogueServiceTest {

    @Test
    void talkIdKeepsItsCaseInChatAndMatchesIgnoringCase() {
        String typed = "{\"text\":\"Hi\"}";
        assertEquals("{\"text\":\"hi\"}", DialogueService.lookupId(typed));
        assertEquals("harbor", DialogueService.lookupId("Harbor"));

        TalkResult unknown = TalkResult.of(TalkCode.UNKNOWN, DialogueService.lookupId(typed));
        TalkResult shown = DialogueService.withDisplayId(unknown, typed);
        assertEquals(typed, shown.characterId());
        assertEquals(TalkCode.UNKNOWN, shown.code());

        TalkResult started = DialogueService.withDisplayId(
                TalkResult.of(TalkCode.STARTED, "harbor"), "Harbor");
        assertEquals("Harbor", started.characterId());
        assertEquals("harbor", DialogueService.withDisplayId(
                TalkResult.of(TalkCode.REPLY, "harbor"), "harbor").characterId());
    }
}
