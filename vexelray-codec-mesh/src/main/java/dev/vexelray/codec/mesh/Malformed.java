package dev.vexelray.codec.mesh;

/** What a reader throws when a file is not what its format says: the message is the reason {@link Meshes} gives. */
final class Malformed extends Exception {

    Malformed(String why) {
        super(why, null, false, false);
    }

    /** At a line of a text file: "line 12: ...". */
    static Malformed at(int line, String why) {
        return new Malformed("line " + line + ": " + why);
    }
}
