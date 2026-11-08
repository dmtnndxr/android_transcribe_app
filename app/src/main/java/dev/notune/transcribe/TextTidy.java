package dev.notune.transcribe;

/**
 * Fixes how a single dictated word lands in text. Speech models treat every
 * recording as a sentence, so one word comes back as "Word." even when it
 * belongs in the middle of a sentence. Longer text is left alone.
 *
 * Free of Android imports so the JVM tests can cover it.
 */
final class TextTidy {

    private TextTidy() {}

    /**
     * A dictated single word, fitted to the text before the cursor: the
     * trailing period goes, and the capital goes too unless the word starts
     * a sentence. {@code before} null means the context is unknown; then
     * only the period is dropped.
     */
    static String fitSingleWord(String text, CharSequence before) {
        String word = text.trim();
        if (!isSingleWord(word)) return text;
        word = dropPeriod(word);
        if (before != null && !startsSentence(before)) word = lowerFirst(word);
        return word;
    }

    /**
     * A voice edit that turned one selected word into another: the reply
     * keeps the selection's lack of a period and its lowercase start.
     */
    static String fitToSelection(String selected, String reply) {
        String sel = selected.trim();
        String word = reply.trim();
        if (!isSingleWord(sel) || !isSingleWord(word)) return reply;
        if (!sel.endsWith(".")) word = dropPeriod(word);
        if (!sel.isEmpty() && Character.isLowerCase(sel.charAt(0))) word = lowerFirst(word);
        return word;
    }

    private static boolean isSingleWord(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    /** "Word." -> "Word"; an ellipsis or an abbreviation like "e.g." stays. */
    private static String dropPeriod(String word) {
        if (word.length() < 2 || !word.endsWith(".")) return word;
        String rest = word.substring(0, word.length() - 1);
        if (rest.indexOf('.') >= 0) return word;
        return rest;
    }

    /**
     * True at the start of the field, after a sentence end, or on a new line.
     */
    private static boolean startsSentence(CharSequence before) {
        int i = before.length() - 1;
        while (i >= 0 && Character.isWhitespace(before.charAt(i))) {
            if (before.charAt(i) == '\n') return true;
            i--;
        }
        if (i < 0) return true;
        char c = before.charAt(i);
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    /**
     * Lowercases the first letter of a capitalized word. Acronyms ("NASA"),
     * single letters and English "I" ("I'm") keep their capitals.
     */
    private static String lowerFirst(String word) {
        if (word.length() < 2) return word;
        char first = word.charAt(0);
        char second = word.charAt(1);
        if (!Character.isUpperCase(first) || Character.isUpperCase(second)) return word;
        if (first == 'I' && (second == '\'' || second == '’')) return word;
        return Character.toLowerCase(first) + word.substring(1);
    }
}
