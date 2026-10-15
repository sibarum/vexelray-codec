package dev.vexelray.codec.mesh;

import java.util.Arrays;

/** Growable primitive arrays: what a reader accumulates a file's numbers in before it knows how many there are. */
final class Grow {

    private Grow() {
    }

    static final class Floats {
        private float[] a = new float[256];
        private int size;

        void add(float v) {
            if (size == a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[size++] = v;
        }

        void add(float x, float y) {
            add(x);
            add(y);
        }

        void add(float x, float y, float z) {
            add(x);
            add(y);
            add(z);
        }

        float get(int i) {
            return a[i];
        }

        int size() {
            return size;
        }

        float[] array() {
            return Arrays.copyOf(a, size);
        }
    }

    static final class Ints {
        private int[] a = new int[256];
        private int size;

        void add(int v) {
            if (size == a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[size++] = v;
        }

        int get(int i) {
            return a[i];
        }

        int size() {
            return size;
        }

        /** The backing array, of which the first {@link #size} are this list: no copy. */
        int[] raw() {
            return a;
        }

        int[] array() {
            return Arrays.copyOf(a, size);
        }
    }
}
