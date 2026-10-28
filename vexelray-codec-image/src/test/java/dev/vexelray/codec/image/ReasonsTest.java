package dev.vexelray.codec.image;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasonsTest {

    private static final String PATH = "C:\\Users\\me\\Pictures\\holiday.gif";

    @Test
    void aReadThatFailedIsSaidWithoutThePath() {
        assertEquals("unreadable: not found", Reasons.unreadable(new NoSuchFileException(PATH)));
        assertEquals("unreadable: access denied", Reasons.unreadable(new AccessDeniedException(PATH)));
        assertEquals("unreadable: in use by another program", Reasons.unreadable(new FileSystemException(PATH, null,
                "The process cannot access the file because it is being used by another process")));
        assertEquals("unreadable: IOException", Reasons.unreadable(new IOException(PATH)));
    }

    @Test
    void whateverWasThrownIsAPlainReason() {
        assertEquals("too large to decode", Reasons.failed(new OutOfMemoryError("Java heap space")));
        assertEquals("the decoder is unavailable", Reasons.failed(new UnsatisfiedLinkError("no imagelib in path")));
        assertEquals("unreadable: not found", Reasons.failed(new NoSuchFileException(PATH)));
        assertEquals("not a video", Reasons.failed(new IOException("not a video")), "a reason already put plainly");
        assertEquals("does not decode", Reasons.failed(new IllegalStateException("could not decode " + PATH)));
        assertEquals("does not decode", Reasons.failed(new StackOverflowError()));
    }
}
