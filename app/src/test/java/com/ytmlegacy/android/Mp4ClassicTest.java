package com.ytmlegacy.android;

import com.ytmlegacy.android.player.Mp4Classic;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Converts real YouTube DASH audio when the samples are present (set -Dmedia.dir=...). */
public class Mp4ClassicTest {
    @Test
    public void convertsDashAudio() throws Exception {
        String dir = System.getProperty("media.dir", System.getenv("MEDIA_DIR") == null ? "" : System.getenv("MEDIA_DIR"));
        Assume.assumeTrue(dir.length() > 0);
        for (String name : new String[]{"a140.m4a", "a139.m4a"}) {
            File in = new File(dir, name);
            Assume.assumeTrue(in.exists());
            File out = new File(dir, "classic_" + name);
            assertTrue(Mp4Classic.isFragmented(in));
            long t = System.currentTimeMillis();
            Mp4Classic.convert(in, out);
            System.out.println(name + " -> " + out.length() + " bytes in " + (System.currentTimeMillis() - t) + " ms");
            assertTrue(out.length() > in.length() * 9 / 10);
            assertFalse(Mp4Classic.isFragmented(out));
        }
    }
}
