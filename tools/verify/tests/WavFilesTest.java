package dev.notune.transcribe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

@DisplayName("WavFiles")
class WavFilesTest {

    @TempDir
    File dir;

    private File wav(int audioBytes, int bytesInHeader) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(44 + audioBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + bytesInHeader).put("WAVEfmt ".getBytes());
        b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        b.putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(bytesInHeader);
        File file = new File(dir, "r.wav");
        Files.write(file.toPath(), b.array());
        return file;
    }

    @Test
    @DisplayName("a killed recording gets a header covering all its audio")
    void repairs() throws Exception {
        File file = wav(80_000, 64_000);
        WavFiles.repair(file);
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(80_036, b.getInt(4));
        assertEquals(80_000, b.getInt(40));
        assertEquals(80_044, file.length());
        assertEquals(2_500, WavFiles.durationMs(file));
    }

    @Test
    @DisplayName("leaves other files untouched")
    void notWav() throws Exception {
        File file = new File(dir, "x.wav");
        byte[] bytes = new byte[100];
        Files.write(file.toPath(), bytes);
        WavFiles.repair(file);
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }

    @Test
    @DisplayName("a bare header has no audio")
    void empty() throws Exception {
        File file = wav(0, 0);
        assertFalse(WavFiles.hasAudio(file));
        assertEquals(0, WavFiles.durationMs(file));
    }
}
