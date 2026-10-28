package dev.vexelray.codec.image;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;

/**
 * Why there is no picture, said as a person would say it: what a viewer shows in place of one, and what a transport
 * shows once a file stops playing.
 *
 * <p><b>Never a path, a class name or a decoder's own words where a phrase will do.</b> A file system's exception
 * carries the path as its message, and a decoder's names the file and then its own internals; shown in a line beside
 * the picture, either says less than "unreadable: not found" or "does not decode". The log keeps the detail.
 */
public final class Reasons {

    private Reasons() {
    }

    /**
     * Why a file would not read: "unreadable: not found", "unreadable: access denied", "unreadable: in use by another
     * program", or the exception's name for anything rarer.
     */
    public static String unreadable(Throwable e) {
        return "unreadable: " + why(e);
    }

    /**
     * Why a file will not open or play, whatever was thrown: a read that failed ({@link #unreadable}), a file too large
     * to hold, a decoder that will not load, or a reason already put plainly (an {@code IOException} of this module's
     * own, whose message is a {@link Decoded#identity}). Anything else does not decode.
     */
    public static String failed(Throwable e) {
        if (e instanceof OutOfMemoryError) {
            return "too large to decode";
        }
        if (e instanceof LinkageError) {
            return "the decoder is unavailable";
        }
        if (e instanceof FileSystemException) {
            return unreadable(e);
        }
        if (e instanceof IOException && e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        if (e instanceof IOException) {
            return unreadable(e);
        }
        return "does not decode";
    }

    private static String why(Throwable e) {
        if (e instanceof NoSuchFileException) {
            return "not found";
        }
        if (e instanceof AccessDeniedException) {
            return "access denied";
        }
        if (e instanceof FileSystemException f && f.getReason() != null) {
            String r = f.getReason().toLowerCase(Locale.ROOT);
            if (r.contains("used by another process") || r.contains("locked")) {
                return "in use by another program";
            }
            if (r.contains("access is denied") || r.contains("permission denied")) {
                return "access denied";
            }
        }
        return e.getClass().getSimpleName();
    }
}
