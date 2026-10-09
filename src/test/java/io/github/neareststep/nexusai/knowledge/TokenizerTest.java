package io.github.neareststep.nexusai.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenizerTest {

    private final Tokenizer tokenizer = Tokenizer.builtin(List.of("dock"));

    @Test
    void dropsShortTokensAndStopWordsAndKeepsUnicode() {
        assertEquals(List.of("rules", "12"), tokenizer.tokens("The a rules 12!"));
        assertEquals(List.of("ёж", "harbor"), tokenizer.tokens("ёж harbor"));
        assertTrue(tokenizer.tokens("как на").isEmpty());
        assertEquals(List.of("подать"), tokenizer.tokens("как подать на"));
        assertTrue(tokenizer.stop("the"));
        assertTrue(tokenizer.stop("dock"));
        assertFalse(tokenizer.tokens("dock fee").contains("dock"));
        assertEquals(List.of("the", "rules"), tokenizer.tokensKeepingStops("the rules"));
    }

    @Test
    void russianStopListIsUniqueAndInCodePointOrder() throws Exception {
        List<String> words = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/main/resources/knowledge/stopwords-ru.txt"))) {
            String word = line.trim();
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        assertEquals(308, words.size());
        assertEquals(words.size(), new HashSet<>(words).size());
        List<String> sorted = new ArrayList<>(words);
        sorted.sort(String::compareTo);
        assertEquals(sorted, words);
        assertTrue(words.indexOf("всего") < words.indexOf("всём"));
        assertTrue(words.indexOf("ей") < words.indexOf("её"));
    }

    @Test
    void cjkRunsBecomeBigrams() {
        assertEquals(List.of("服务", "务器", "器规", "规则"), tokenizer.tokens("服务器规则"));
        assertEquals(List.of("あい"), tokenizer.tokens("あい"));
        assertTrue(tokenizer.tokens("あ").isEmpty());
        assertEquals(List.of("규칙"), tokenizer.tokens("규칙"));
    }

    @Test
    void builtinListsHoldAboutOneHundredFiftyWords() {
        assertTrue(Tokenizer.resourceWords("knowledge/stopwords-en.txt") >= 140);
        assertTrue(Tokenizer.resourceWords("knowledge/stopwords-ru.txt") >= 140);
    }
}
