package dev.notune.transcribe;

import android.content.Context;

import java.util.Locale;

/** Ready-made AI post-processing prompts the user can start from. */
final class PromptTemplates {

    private PromptTemplates() {}

    static final String FORMAL =
            "Rewrite the dictated text below in a polite, formal tone. Fix grammar and "
            + "punctuation and keep the meaning and the language. Reply with the rewritten "
            + "text only, with no preamble, quotes or commentary.\n\n${output}";

    static final String BULLETS =
            "Turn the dictated text below into a short bullet list in the same language. "
            + "Fix obvious speech-to-text errors. Reply with the list only, with no preamble "
            + "or commentary.\n\n${output}";

    /** Translates whatever was dictated into {@code language} (an English name). */
    static String translate(String language) {
        return "You are a translator. Translate the dictated text below into " + language
                + ". Keep the meaning and tone and fix obvious speech-to-text errors. Reply "
                + "with the translation only, with no preamble, quotes or commentary."
                + "\n\n${output}";
    }

    /** The phone's language in English ("Russian"), or null if it is English. */
    static String deviceLanguage(Context ctx) {
        Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
        if (locale == null || locale.getLanguage().equals("en")) return null;
        String name = locale.getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() ? null : name;
    }

    /** Labels and prompts, in menu order. */
    static String[][] all(Context ctx) {
        String device = deviceLanguage(ctx);
        String[][] base = {
                {ctx.getString(R.string.pp_template_cleanup), PostProcessPrefs.DEFAULT_PROMPT},
                {ctx.getString(R.string.pp_template_translate,
                        Locale.ENGLISH.getDisplayLanguage(Locale.getDefault())), translate("English")},
                {ctx.getString(R.string.pp_template_formal), FORMAL},
                {ctx.getString(R.string.pp_template_bullets), BULLETS},
        };
        if (device == null) return base;
        String[][] out = new String[base.length + 1][];
        out[0] = base[0];
        out[1] = base[1];
        out[2] = new String[]{ctx.getString(R.string.pp_template_translate,
                Locale.getDefault().getDisplayLanguage()), translate(device)};
        System.arraycopy(base, 2, out, 3, base.length - 2);
        return out;
    }
}
