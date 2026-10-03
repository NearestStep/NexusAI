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

    @Test
    void laterSessionLinesKeepTheOpeningSpelling() {
        assertEquals("Harbor", DialogueService.sessionLabel(null, "Harbor"));
        assertEquals("Harbor", DialogueService.sessionLabel("Harbor", "harbor"));
        assertEquals("Smith", DialogueService.sessionLabel("Harbor", "Smith"));
        String opening = "Harbor";
        String later = DialogueService.sessionLabel(opening, "harbor");
        TalkResult reply = DialogueService.withDisplayId(TalkResult.of(TalkCode.REPLY, "harbor"), later);
        assertEquals("Harbor", reply.characterId());
        TalkResult started = DialogueService.withDisplayId(TalkResult.of(TalkCode.STARTED, "harbor"), opening);
        assertEquals(started.characterId(), reply.characterId());
    }
}
