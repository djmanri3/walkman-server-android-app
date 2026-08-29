package com.djmanri3.Walkman;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Extrae los metadatos (título, artista, álbum, carátula) incrustados en un
 * archivo de audio. Soporta ID3v1, ID3v2.2/2.3/2.4 (MP3/OGG) y
 * VORBIS_COMMENT + PICTURE (FLAC). Aplica codificaciones del texto
 * (ISO-8859-1, UTF-8, UTF-16).
 */
public final class EmbeddedCover {

    private static final byte[] ID3 = {'I', 'D', '3'};
    private static final byte[] FLAC = {'f', 'L', 'a', 'C'};

    private EmbeddedCover() {
    }

    /** Metadatos extraídos de un archivo de audio. */
    public static final class AudioMeta {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String albumArtist = "";
        public String genre = "";
        public String year = "";
        public String track = "";
        public byte[] coverBytes;
        public String coverMime = "";
    }

    /**
     * Lee los metadatos del archivo. Nunca lanza excepciones; devuelve un
     * {@link AudioMeta} con campos vacíos si no puede leer nada.
     */
    public static AudioMeta extract(ContentResolver cr, Uri uri) {
        AudioMeta meta = new AudioMeta();
        if (cr == null || uri == null) {
            return meta;
        }
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                return meta;
            }
            byte[] head = new byte[16];
            int n = readFully(in, head, 0, head.length);
            if (n >= 3 && matches(head, 0, ID3)) {
                id3v2(in, head, n, meta);
            } else if (n >= 4 && matches(head, 0, FLAC)) {
                flac(in, head, n, meta);
            }
            if (meta.title.length() == 0 || meta.artist.length() == 0
                    || meta.album.length() == 0) {
                id3v1Fallback(cr, uri, meta);
            }
        } catch (Exception ignored) {
        }
        return meta;
    }

    /* ------------------------------------------------------------------ */
    /* ID3v2                                                              */
    /* ------------------------------------------------------------------ */

    private static void id3v2(InputStream in, byte[] head, int n, AudioMeta meta) {
        try {
            boolean unsync = (head[5] & 0x80) != 0;
            boolean extHeader = (head[5] & 0x40) != 0;
            int major = head[3] & 0xFF;
            int id3size = synchsafe(head[6]) << 21
                    | synchsafe(head[7]) << 14
                    | synchsafe(head[8]) << 7
                    | synchsafe(head[9]);
            if (id3size <= 0 || id3size > 32 * 1024 * 1024) {
                return;
            }
            byte[] body = new byte[id3size];
            int copied = Math.min(id3size, Math.max(0, n - 10));
            if (copied > 0) {
                System.arraycopy(head, 10, body, 0, copied);
            }
            if (copied < id3size) {
                readFully(in, body, copied, id3size - copied);
            }

            int pos = 0;
            if (extHeader && major >= 3) {
                if (pos + 4 <= body.length) {
                    int extSize = major == 4
                            ? synchsafe(body[pos]) << 21 | synchsafe(body[pos + 1]) << 14
                            | synchsafe(body[pos + 2]) << 7 | synchsafe(body[pos + 3])
                            : intBE(body, pos);
                    pos += 4 + extSize;
                }
            }

            byte[] data = body;
            while (pos + 10 <= body.length) {
                // v2.2 usa ids de 3 bytes y tamaños de 3.
                boolean v22 = major == 2;
                int headerLen = v22 ? 6 : 10;
                if (pos + headerLen > body.length) {
                    break;
                }
                String fid;
                int fsize;
                int dataStart;
                if (v22) {
                    fid = new String(body, pos, 3, "ISO-8859-1");
                    fsize = (body[pos + 3] & 0xFF) << 16
                            | (body[pos + 4] & 0xFF) << 8
                            | (body[pos + 5] & 0xFF);
                    dataStart = pos + 6;
                } else {
                    fid = new String(body, pos, 4, "ISO-8859-1");
                    int sz = major == 4
                            ? synchsafe(body[pos + 4]) << 21 | synchsafe(body[pos + 5]) << 14
                            | synchsafe(body[pos + 6]) << 7 | synchsafe(body[pos + 7])
                            : intBE(body, pos + 4);
                    fsize = sz;
                    dataStart = pos + 10;
                }
                if (fsize < 0 || dataStart + fsize > body.length) {
                    break;
                }
                byte[] fdata = body;
                int foff = dataStart;
                if (unsync && fsize > 0) {
                    fdata = deUnsync(body, dataStart, fsize);
                    foff = 0;
                }
                handleFrame(fid, fdata, foff, fsize, meta);
                if (fid.equals("APIC")) {
                    // las portadas se capturan en handleFrame; no rompemos.
                }
                pos = dataStart + fsize;
            }
        } catch (Exception ignored) {
        }
    }

    private static void handleFrame(String fid, byte[] data, int off, int size, AudioMeta meta) {
        try {
            switch (fid) {
                case "TIT2":
                case "TT2":
                    meta.title = decodeText(data, off, size);
                    break;
                case "TPE1":
                case "TP1":
                    meta.artist = decodeText(data, off, size);
                    break;
                case "TALB":
                case "TAL":
                    meta.album = decodeText(data, off, size);
                    break;
                case "TPE2":
                case "TP2":
                    meta.albumArtist = decodeText(data, off, size);
                    break;
                case "TCON":
                case "TCO":
                    meta.genre = decodeText(data, off, size);
                    break;
                case "TDRC":
                case "TYER":
                    meta.year = decodeText(data, off, size).replaceAll("\\D", "");
                    break;
                case "TRCK":
                case "TRK":
                    meta.track = decodeText(data, off, size);
                    break;
                case "APIC": {
                    if (off + 4 >= data.length) {
                        return;
                    }
                    int p = off;
                    int enc = data[p++] & 0xFF;
                    int mimeEnd = indexOf(data, p, (byte) 0);
                    if (mimeEnd < 0) {
                        return;
                    }
                    meta.coverMime = new String(data, p, mimeEnd - p, "ISO-8859-1");
                    p = mimeEnd + 1;
                    p++; // picture type
                    int step = enc == 1 || enc == 2 ? 2 : 1;
                    int descEnd = indexOfStep(data, p, (byte) 0, step);
                    if (descEnd < 0) {
                        return;
                    }
                    int imgStart = descEnd + step;
                    if (imgStart < data.length) {
                        byte[] img = new byte[data.length - imgStart];
                        System.arraycopy(data, imgStart, img, 0, img.length);
                        meta.coverBytes = img;
                    }
                    break;
                }
                case "PIC": {
                    int p = off + 1; // encoding
                    p += 3; // image format
                    p++; // picture type
                    int descEnd = indexOf(data, p, (byte) 0);
                    if (descEnd < 0) {
                        return;
                    }
                    int imgStart = descEnd + 1;
                    if (imgStart < data.length) {
                        // adivinar mime por el formato de 3 letras
                        String fmt = data.length > off + 4
                                ? new String(data, off + 1, 3, "ISO-8859-1").toLowerCase()
                                : "jpg";
                        meta.coverMime = "image/" + (fmt.equals("jpg") || fmt.equals("jpeg") ? "jpeg" : fmt);
                        byte[] img = new byte[data.length - imgStart];
                        System.arraycopy(data, imgStart, img, 0, img.length);
                        meta.coverBytes = img;
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private static String decodeText(byte[] data, int off, int size) {
        if (off >= data.length) {
            return "";
        }
        try {
            int bounded = Math.min(size, data.length - off);
            if (bounded <= 0) {
                return "";
            }
            int enc = data[off] & 0xFF;
            int start = off + 1;
            if (start > data.length) {
                return "";
            }
            int textLen = Math.min(bounded - 1, data.length - start);
            if (textLen <= 0) {
                return "";
            }
            String s = "";
            if (enc == 3) {
                s = new String(data, start, textLen, StandardCharsets.UTF_8);
            } else if (enc == 2) {
                Charset cs;
                try {
                    cs = Charset.forName("UTF-16BE");
                } catch (Exception e) {
                    cs = StandardCharsets.UTF_16;
                }
                s = new String(data, start, textLen, cs);
            } else if (enc == 1) {
                // UTF-16 con BOM
                Charset cs;
                try {
                    cs = Charset.forName("UTF-16");
                } catch (Exception e) {
                    cs = StandardCharsets.UTF_16;
                }
                s = new String(data, start, textLen, cs);
            } else {
                s = new String(data, start, textLen, "ISO-8859-1");
            }
            return s.replace('\u0000', ' ').trim();
        } catch (Exception e) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ */
    /* FLAC                                                               */
    /* ------------------------------------------------------------------ */

    private static void flac(InputStream in, byte[] head, int n, AudioMeta meta) {
        try {
            int pos = 4;
            boolean firstBlock = true;
            while (true) {
                byte[] blockHead = new byte[4];
                if (pos < n) {
                    int avail = Math.min(4, n - pos);
                    System.arraycopy(head, pos, blockHead, 0, avail);
                    if (avail < 4) {
                        readFully(in, blockHead, avail, 4 - avail);
                    }
                } else {
                    readFully(in, blockHead, 0, 4);
                }
                pos += Math.min(4, Math.max(0, n - pos));
                boolean last = (blockHead[0] & 0x80) != 0;
                int type = blockHead[0] & 0x7F;
                int len = (blockHead[1] & 0xFF) << 16
                        | (blockHead[2] & 0xFF) << 8
                        | (blockHead[3] & 0xFF);
                // el primer bloque es STREAMINFO; los demás pueden estar antes que él
                // en archivos raros; con firstBlock evitamos leer STREAMINFO(0) como comment.
                if (len > 0) {
                    byte[] data = new byte[Math.min(len, 4 * 1024 * 1024)];
                    readFully(in, data, 0, data.length);
                    if (type == 4) {
                        vorbisComment(data, meta);
                    } else if (type == 6) {
                        pictureBlock(data, meta);
                    } else if (len > data.length) {
                        long remain = len - data.length;
                        skipFully(in, remain);
                    }
                }
                pos += len;
                if (last) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void vorbisComment(byte[] data, AudioMeta meta) {
        try {
            int pos = 0;
            int vendorLen = intLE(data, pos);
            pos += 4 + vendorLen;
            if (pos + 4 > data.length) {
                return;
            }
            int count = intLE(data, pos);
            pos += 4;
            for (int i = 0; i < count && pos + 4 <= data.length; i++) {
                int len = intLE(data, pos);
                pos += 4;
                if (pos + len > data.length) {
                    return;
                }
                String kv = new String(data, pos, len, StandardCharsets.UTF_8);
                pos += len;
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = kv.substring(0, eq).toUpperCase();
                String val = kv.substring(eq + 1);
                switch (key) {
                    case "TITLE": if (meta.title.length() == 0) meta.title = val; break;
                    case "ARTIST": if (meta.artist.length() == 0) meta.artist = val; break;
                    case "ALBUM": if (meta.album.length() == 0) meta.album = val; break;
                    case "ALBUMARTIST": if (meta.albumArtist.length() == 0) meta.albumArtist = val; break;
                    case "GENRE": if (meta.genre.length() == 0) meta.genre = val; break;
                    case "DATE":
                    case "YEAR": if (meta.year.length() == 0) meta.year = val.replaceAll("\\D", ""); break;
                    case "TRACKNUMBER": if (meta.track.length() == 0) meta.track = val; break;
                    default: break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void pictureBlock(byte[] data, AudioMeta meta) {
        try {
            if (meta.coverBytes != null) {
                return;
            }
            int pos = 4; // picture type
            int mimeLen = intBE(data, pos);
            pos += 4;
            meta.coverMime = new String(data, pos, mimeLen, "ISO-8859-1");
            pos += mimeLen;
            int descLen = intBE(data, pos);
            pos += 4 + descLen;
            long picLen = uintBE(data, pos);
            pos += 4;
            if (pos + picLen <= data.length && picLen > 0) {
                byte[] img = new byte[(int) picLen];
                System.arraycopy(data, pos, img, 0, img.length);
                meta.coverBytes = img;
            }
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* ID3v1                                                              */
    /* ------------------------------------------------------------------ */

    private static void id3v1Fallback(ContentResolver cr, Uri uri, AudioMeta meta) {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                return;
            }
            long size;
            android.content.res.AssetFileDescriptor afd =
                    cr.openAssetFileDescriptor(uri, "r");
            if (afd != null) {
                size = afd.getLength();
                afd.close();
            } else {
                return;
            }
            if (size < 128) {
                return;
            }
            long skip = size - 128;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) {
                    break;
                }
                skip -= s;
            }
            byte[] tag = new byte[128];
            readFully(in, tag, 0, 128);
            if (matches(tag, 0, new byte[]{'T', 'A', 'G'})) {
                if (meta.title.length() == 0) meta.title = ascii(tag, 3, 30);
                if (meta.artist.length() == 0) meta.artist = ascii(tag, 33, 30);
                if (meta.album.length() == 0) meta.album = ascii(tag, 63, 30);
                if (meta.year.length() == 0 && tag[97] != 0) meta.year = ascii(tag, 93, 4);
                if (meta.track.length() == 0 && tag.length >= 126 && tag[125] != 0) {
                    meta.track = String.valueOf(tag[125] & 0xFF);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String ascii(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) {
            if (b[i] == 0) {
                break;
            }
            sb.append((char) (b[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static void skipFully(InputStream in, long remain) throws java.io.IOException {
        while (remain > 0) {
            long r = in.skip(remain);
            if (r > 0) {
                remain -= r;
            } else {
                if (in.read() < 0) {
                    return;
                }
                remain--;
            }
        }
    }

    private static int synchsafe(int b) {
        return b & 0x7F;
    }

    private static int intBE(byte[] b, int off) {
        if (off + 4 > b.length) {
            return 0;
        }
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16
                | (b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF);
    }

    private static int intLE(byte[] b, int off) {
        if (off + 4 > b.length) {
            return 0;
        }
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static long uintBE(byte[] b, int off) {
        if (off + 4 > b.length) {
            return 0;
        }
        return ((long) (b[off] & 0xFF) << 24) | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8) | (long) (b[off + 3] & 0xFF);
    }

    private static boolean matches(byte[] hay, int off, byte[] needle) {
        if (hay.length - off < needle.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (hay[off + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] arr, int from, byte target) {
        for (int i = from; i < arr.length; i++) {
            if (arr[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfStep(byte[] arr, int from, byte target, int step) {
        for (int i = from; i < arr.length; i += step) {
            if (arr[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] deUnsync(byte[] body, int start, int len) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        try {
            for (int i = start; i < start + len && i < body.length; i++) {
                out.write(body[i]);
                if (body[i] == (byte) 0xFF && i + 1 < body.length
                        && body[i + 1] == 0x00) {
                    i++;
                }
            }
        } catch (Exception ignored) {
        }
        return out.toByteArray();
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) {
        int total = 0;
        try {
            while (total < len) {
                int r = in.read(b, off + total, len - total);
                if (r < 0) {
                    break;
                }
                total += r;
            }
        } catch (Exception ignored) {
        }
        return total;
    }
}