package dev.notune.transcribe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("TextTidy")
class TextTidyTest {

    @Nested
    @DisplayName("fitSingleWord")
    class FitSingleWord {

        @Test
        @DisplayName("mid-sentence: drops the period and the capital")
        void midSentence() {
            assertEquals("завтра", TextTidy.fitSingleWord("Завтра.", "Встретимся "));
            assertEquals("tomorrow", TextTidy.fitSingleWord(" Tomorrow. ", "See you"));
        }

        @Test
        @DisplayName("sentence start: keeps the capital, drops the period")
        void sentenceStart() {
            assertEquals("Привет", TextTidy.fitSingleWord("Привет.", ""));
            assertEquals("Hello", TextTidy.fitSingleWord("Hello.", "Done. "));
            assertEquals("Hello", TextTidy.fitSingleWord("Hello.", "Done?"));
            assertEquals("Hello", TextTidy.fitSingleWord("Hello.", "first line\n"));
        }

        @Test
        @DisplayName("unknown context: only the period goes")
        void unknownContext() {
            assertEquals("Привет", TextTidy.fitSingleWord("Привет.", null));
        }

        @Test
        @DisplayName("keeps questions, ellipses, abbreviations and acronyms")
        void keeps() {
            assertEquals("правда?", TextTidy.fitSingleWord("Правда?", "Это "));
            assertEquals("ну…", TextTidy.fitSingleWord("Ну…", "А "));
            assertEquals("e.g.", TextTidy.fitSingleWord("e.g.", "words "));
            assertEquals("Wait...", TextTidy.fitSingleWord("Wait...", ""));
            assertEquals("NASA", TextTidy.fitSingleWord("NASA.", "at "));
            assertEquals("I'm", TextTidy.fitSingleWord("I'm.", "and "));
        }

        @Test
        @DisplayName("leaves longer text alone")
        void longer() {
            assertEquals("Два слова.", TextTidy.fitSingleWord("Два слова.", "текст "));
        }
    }

    @Nested
    @DisplayName("fitToSelection")
    class FitToSelection {

        @Test
        @DisplayName("follows the selected word's case and missing period")
        void followsSelection() {
            assertEquals("собака", TextTidy.fitToSelection("кот", "Собака."));
            assertEquals("Собака", TextTidy.fitToSelection("Кот", "Собака."));
            assertEquals("Собака.", TextTidy.fitToSelection("Кот.", "Собака."));
        }

        @Test
        @DisplayName("leaves multi-word selections and replies alone")
        void multiWord() {
            assertEquals("Большая собака.", TextTidy.fitToSelection("кот", "Большая собака."));
            assertEquals("Пёс.", TextTidy.fitToSelection("рыжий кот", "Пёс."));
        }
    }
}
