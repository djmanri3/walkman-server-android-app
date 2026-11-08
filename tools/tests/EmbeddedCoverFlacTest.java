package com.djmanri3.Walkman;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Runs on the JVM with android.jar; exercises only the stream parser. */
public class EmbeddedCoverFlacTest {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static byte[] block(int type, byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type); out.write(data.length >> 16); out.write(data.length >> 8); out.write(data.length); out.write(data);
        return out.toByteArray();
    }
    static byte[] join(byte[]... chunks) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk);
        return out.toByteArray();
    }
    static void le(DataOutputStream out, int n) throws Exception { out.writeInt(Integer.reverseBytes(n)); }
    static byte[] streamInfo(int rate, int depth, int channels) throws Exception {
        byte[] data = new byte[34];
        data[10] = (byte)(rate >>> 12);
        data[11] = (byte)(rate >>> 4);
        data[12] = (byte)((rate & 15) << 4 | (channels - 1) << 1 | (depth - 1) >>> 4);
        data[13] = (byte)((depth - 1) << 4);
        return block(0, data);
    }
    static byte[] comments() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        le(out, 0); le(out, 3);
        for (String tag : new String[]{"TITLE=Test song", "ARTIST=Test artist", "ALBUM=Test album"}) {
            byte[] b = tag.getBytes(StandardCharsets.UTF_8); le(out, b.length); out.write(b);
        }
        return bytes.toByteArray();
    }
    static EmbeddedCover.AudioMeta parse(byte[] bytes) throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        byte[] head = new byte[16]; int n = in.read(head);
        Method method = EmbeddedCover.class.getDeclaredMethod("flac", InputStream.class, byte[].class, int.class, EmbeddedCover.AudioMeta.class);
        method.setAccessible(true);
        EmbeddedCover.AudioMeta meta = new EmbeddedCover.AudioMeta();
        method.invoke(null, in, head, n, meta); return meta;
    }
    public static void main(String[] args) throws Exception {
        byte[] signature = {'f','L','a','C'};
        byte[] streamInfo = streamInfo(96000, 24, 2);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream picture = new DataOutputStream(bytes);
        picture.writeInt(3); picture.writeInt(10); picture.writeBytes("image/jpeg"); picture.writeInt(0);
        picture.writeInt(1); picture.writeInt(1); picture.writeInt(24); picture.writeInt(0);
        byte[] image = {1,2,3,4}; picture.writeInt(image.length); picture.write(image);
        byte[] valid = join(signature, streamInfo, block(4, comments()), block(128 | 6, bytes.toByteArray()));
        EmbeddedCover.AudioMeta meta = parse(valid);
        require(meta.title.equals("Test song") && meta.artist.equals("Test artist") && meta.album.equals("Test album"), "FLAC blocks must remain aligned after the 16-byte prefetch");
        require(Arrays.equals(meta.coverBytes, image), "Read picture data after its dimensions");
        require(meta.codec.equals("flac") && meta.sampleRate == 96000 && meta.bitDepth == 24 && meta.channels == 2, "Read real FLAC source quality from STREAMINFO");
        for (int[] quality : new int[][]{{44100,16,2},{192000,24,2},{48000,32,6}}) {
            EmbeddedCover.AudioMeta q = parse(join(signature, streamInfo(quality[0],quality[1],quality[2]), block(128 | 4, comments())));
            require(q.sampleRate == quality[0] && q.bitDepth == quality[1] && q.channels == quality[2], "Preserve rate, depth and channels without guessing from extension");
        }
        require(parse(join(signature, block(128, new byte[34]))).sampleRate == 0, "Invalid STREAMINFO must not invent quality");
        parse(join(signature, streamInfo)); // EOF without a last-block flag must terminate.
        parse(Arrays.copyOf(valid, 30)); // Truncated STREAMINFO must terminate.
        parse(join(signature, streamInfo, new byte[]{4, 0, 1})); // Truncated header.
        byte[] large = new byte[4 * 1024 * 1024 + 1];
        meta = parse(join(signature, streamInfo, block(6, large), block(128 | 4, comments())));
        require(meta.title.equals("Test song"), "Skip the entire oversized picture, keeping the next block aligned");
        System.out.println("PASS: FLAC tags, cover, truncated data, EOF and oversized picture");
    }
}
