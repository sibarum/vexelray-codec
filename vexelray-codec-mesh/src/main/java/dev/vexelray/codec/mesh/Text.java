package dev.vexelray.codec.mesh;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The text formats' lexer: a file's bytes as lines of whitespace-separated tokens, a comment character's rest of the
 * line left out. A token is ASCII in every format read here, so the bytes are never decoded as a whole, and a line is
 * its tokens' offsets until one is asked for as a string or a number.
 *
 * <p>Two ways through: {@link #nextLine} a line at a time, for formats whose lines mean something (OBJ, OFF), and
 * {@link #nextToken} a token at a time across lines, for those whose line breaks do not (an ASCII PLY body, STL).
 */
final class Text {

    private final byte[] b;
    private final int end;
    private final int comment;
    private int pos;
    private int line;
    private int[] starts = new int[16];
    private int[] ends = new int[16];
    private int count;
    private int cursor;

    /** {@code b} from {@code from} to {@code to}, with {@code comment} starting a comment, or -1 for none. */
    Text(byte[] b, int from, int to, int comment) {
        this.b = b;
        this.pos = from;
        this.end = to;
        this.comment = comment;
    }

    /** On to the next line, empty or not; false at the end of the file. */
    boolean nextLine() {
        if (pos >= end) {
            return false;
        }
        line++;
        count = 0;
        cursor = 0;
        boolean commented = false;
        int i = pos;
        while (i < end && b[i] != '\n') {
            int c = b[i] & 0xFF;
            if (c == comment) {
                commented = true;
            }
            if (commented || c <= ' ') {
                i++;
                continue;
            }
            int s = i;
            while (i < end && (b[i] & 0xFF) > ' ' && (b[i] & 0xFF) != comment) {
                i++;
            }
            if (count == starts.length) {
                starts = Arrays.copyOf(starts, count * 2);
                ends = Arrays.copyOf(ends, count * 2);
            }
            starts[count] = s;
            ends[count] = i;
            count++;
        }
        pos = i + 1;
        return true;
    }

    /** The next token, across lines, or null at the end of the file. */
    String nextToken() {
        while (cursor >= count) {
            if (!nextLine()) {
                return null;
            }
        }
        return token(cursor++);
    }

    /** The next token as a number, across lines. */
    float nextNumber(String what) throws Malformed {
        String t = nextToken();
        if (t == null) {
            throw Malformed.at(line, "the file ends where " + what + " should be");
        }
        return parse(t, what);
    }

    /** The next token as a number at full precision, across lines: what an index or a count must be read as. */
    double nextValue(String what) throws Malformed {
        String t = nextToken();
        if (t == null) {
            throw Malformed.at(line, "the file ends where " + what + " should be");
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw Malformed.at(line, what + " is not a number: " + clip(t));
        }
    }

    /** Whether tokens are left on this line that {@link #nextToken} has not given. */
    boolean moreOnLine() {
        return cursor < count;
    }

    /** The current line's number, from 1. */
    int line() {
        return line;
    }

    /** How many tokens the current line has. */
    int size() {
        return count;
    }

    /** Token {@code i} of the current line. */
    String token(int i) {
        return new String(b, starts[i], ends[i] - starts[i], StandardCharsets.ISO_8859_1);
    }

    /** Whether token {@code i} of the current line is {@code s}, without making a string of it. */
    boolean is(int i, String s) {
        int n = ends[i] - starts[i];
        if (n != s.length()) {
            return false;
        }
        for (int k = 0; k < n; k++) {
            if (b[starts[i] + k] != s.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    /** Token {@code i} of the current line as a number, or why it is not one. */
    float number(int i, String what) throws Malformed {
        return parse(token(i), what);
    }

    /** Token {@code i} of the current line as a whole number, or why it is not one. */
    int integer(int i, String what) throws Malformed {
        String t = token(i);
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw Malformed.at(line, what + " is not a whole number: " + clip(t));
        }
    }

    private float parse(String t, String what) throws Malformed {
        try {
            return Float.parseFloat(t);
        } catch (NumberFormatException e) {
            throw Malformed.at(line, what + " is not a number: " + clip(t));
        }
    }

    /** A token as far as a reason quotes it: a binary file read as text has very long ones. */
    static String clip(String t) {
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }
}
