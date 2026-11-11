package dev.neosync.boox.library;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a book's file name into a readable title. */
public final class TitleCleaner {

    private static final Pattern TRAILING_GROUP = Pattern.compile("\\s*\\(([^()]*)\\)\\s*$");
    private static final Pattern DOMAIN = Pattern.compile("[\\w-]+(\\.[\\w-]+)+");

    private TitleCleaner() {
    }

    public static String fromFileName(String fileName) {
        String s = fileName;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        while (true) {
            Matcher m = TRAILING_GROUP.matcher(s);
            if (!m.find() || !allDomains(m.group(1))) break;
            s = s.substring(0, m.start());
        }
        s = s.trim();
        if (s.endsWith("_")) s = s.substring(0, s.length() - 1) + "…";
        s = s.replace('_', ' ').trim();
        return s.isEmpty() ? fileName : s;
    }

    private static boolean allDomains(String inside) {
        for (String token : inside.split(",", -1)) {
            if (!DOMAIN.matcher(token.trim()).matches()) return false;
        }
        return true;
    }
}
