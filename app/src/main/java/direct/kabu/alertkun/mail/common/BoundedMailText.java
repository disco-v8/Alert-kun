package direct.kabu.alertkun.mail.common;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.Locale;

/** A decoded, code-point-safe UTF-8 prefix. Never retains the source text or HTML. */
public final class BoundedMailText {
    public static final int MAX_BYTES = 16384;
    private final StringBuilder text = new StringBuilder(256);
    private int bytes;
    private boolean stopped;
    private final int limit;

    public BoundedMailText() { this(MAX_BYTES); }
    public BoundedMailText(int limit) { this.limit = Math.max(0, Math.min(MAX_BYTES, limit)); }
    public int remainingForPart() { return Math.max(0, limit - bytes - (text.length() == 0 ? 0 : 1)); }

    public boolean isFull() { return stopped || bytes == limit; }
    public String value() { return text.toString(); }
    public void clear() { text.setLength(0); bytes = 0; stopped = false; }
    private void appendCodePoint(int cp) {
        if (isFull()) return;
        if (cp >= 0xd800 && cp <= 0xdfff || !Character.isValidCodePoint(cp)) cp = 0xfffd;
        int size = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
        if (bytes + size > limit) { stopped = true; return; }
        text.appendCodePoint(cp);
        bytes += size;
    }
    public void append(String value) {
        if (value.isEmpty() || isFull()) return;
        if (text.length() > 0) appendCodePoint('\n');
        for (int i = 0; i < value.length() && !isFull();) {
            int cp = value.codePointAt(i);
            appendCodePoint(cp);
            i += Character.charCount(cp);
        }
    }
    private static int codePoint(PushbackReader reader) throws IOException {
        int first = reader.read();
        if (first < 0) return -1;
        if (!Character.isHighSurrogate((char) first)) return Character.isLowSurrogate((char) first) ? 0xfffd : first;
        int second = reader.read();
        if (second >= 0 && Character.isLowSurrogate((char) second)) return Character.toCodePoint((char) first, (char) second);
        if (second >= 0) reader.unread(second);
        return 0xfffd;
    }
    public void read(Reader source, boolean html) throws IOException {
        if (isFull()) return;
        PushbackReader reader = new PushbackReader(source, 2);
        if (!html) {
            int cp;
            while (!isFull() && (cp = codePoint(reader)) >= 0) appendCodePoint(cp);
            return;
        }
        // A streaming tag filter. Tag scratch space is fixed; entity spelling follows the existing textifier.
        String suppressed = null;
        boolean pendingSpace = false;
        int cp;
        while (!isFull() && (cp = codePoint(reader)) >= 0) {
            if (cp == '<') {
                StringBuilder tag = new StringBuilder(32);
                int quote = 0;
                while ((cp = codePoint(reader)) >= 0) {
                    if (quote == 0 && cp == '>') break;
                    if (cp == '\'' || cp == '"') {
                        if (quote == 0) quote = cp; else if (quote == cp) quote = 0;
                    }
                    if (tag.length() < 32) tag.appendCodePoint(cp);
                }
                String name = tag.toString().trim().toLowerCase(Locale.ROOT).split("[\\s/]", 2)[0];
                String raw = tag.toString().trim().toLowerCase(Locale.ROOT);
                if (suppressed != null) {
                    if (raw.startsWith("/" + suppressed) && (raw.length() == suppressed.length() + 1 ||
                            Character.isWhitespace(raw.charAt(suppressed.length() + 1)))) suppressed = null;
                } else if (name.equals("script") || name.equals("style")) suppressed = name;
                pendingSpace = text.length() > 0;
                continue;
            }
            if (suppressed != null) continue;
            if (Character.isWhitespace(cp)) { pendingSpace = text.length() > 0; continue; }
            if (pendingSpace) appendCodePoint(' ');
            pendingSpace = false;
            appendCodePoint(cp);
        }
    }
}
