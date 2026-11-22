package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Salvages recordings (mono 16 kHz 16-bit WAV) whose process died before it
 * could finish the header.
 *
 * Free of Android imports so the JVM tests can cover it.
 */
final class WavFiles {

    static final int HEADER_BYTES = 44;
    private static final int BYTES_PER_SECOND = 32_000;

    private WavFiles() {}

    static boolean hasAudio(File file) {
        return file != null && file.length() > HEADER_BYTES;
    }

    static long durationMs(File file) {
        return hasAudio(file) ? (file.length() - HEADER_BYTES) * 1000 / BYTES_PER_SECOND : 0;
    }

    /** Makes the header cover all the audio that reached the disk. */
    static void repair(File file) throws IOException {
        if (!hasAudio(file)) return;
        try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
            if (f.readInt() != 0x52494646) return; // not "RIFF"
            long data = Math.min((f.length() - HEADER_BYTES) & ~1L, 0xFFFF_FFFFL - 36);
            f.seek(40);
            if ((Integer.reverseBytes(f.readInt()) & 0xFFFF_FFFFL) == data) return;
            f.seek(4);
            f.writeInt(Integer.reverseBytes((int) (36 + data)));
            f.seek(40);
            f.writeInt(Integer.reverseBytes((int) data));
        }
    }
}
