package com.djmanri3.Walkman;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

/** Reads stream headers only; never decodes a complete song to detect its quality. */
final class LocalAudioQuality {
    private LocalAudioQuality() {}

    static void extract(ContentResolver resolver, Uri uri, EmbeddedCover.AudioMeta meta) {
        MediaExtractor extractor = new MediaExtractor();
        try (AssetFileDescriptor file = resolver.openAssetFileDescriptor(uri, "r")) {
            if (file == null) return;
            if (file.getLength() >= 0) extractor.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            else if (file.getStartOffset() == 0) extractor.setDataSource(file.getFileDescriptor());
            else return;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                meta.codec = codec(mime);
                meta.sampleRate = integer(format, MediaFormat.KEY_SAMPLE_RATE);
                meta.channels = integer(format, MediaFormat.KEY_CHANNEL_COUNT);
                meta.bitrate = integer(format, MediaFormat.KEY_BIT_RATE);
                meta.bitDepth = integer(format, "bits-per-sample");
                if (meta.bitDepth == 0 && meta.codec.equals("pcm")) {
                    switch (integer(format, MediaFormat.KEY_PCM_ENCODING)) {
                        case AudioFormat.ENCODING_PCM_8BIT: meta.bitDepth = 8; break;
                        case AudioFormat.ENCODING_PCM_16BIT: meta.bitDepth = 16; break;
                        case AudioFormat.ENCODING_PCM_24BIT_PACKED: meta.bitDepth = 24; break;
                        case AudioFormat.ENCODING_PCM_32BIT:
                        case AudioFormat.ENCODING_PCM_FLOAT: meta.bitDepth = 32; break;
                        default: break;
                    }
                }
                return;
            }
        } catch (Exception ignored) {
            // Unsupported or unreadable files retain unknown quality, never an invented Hi-Res flag.
        } finally {
            extractor.release();
        }
    }

    private static int integer(MediaFormat format, String key) {
        return format.containsKey(key) ? Math.max(0, format.getInteger(key)) : 0;
    }

    private static String codec(String mime) {
        switch (mime) {
            case "audio/mpeg": return "mp3";
            case "audio/mp4a-latm": return "aac";
            case "audio/raw": return "pcm";
            case "audio/x-flac": return "flac";
            case "audio/x-alac": return "alac";
            case "audio/x-ms-wma": return "wma";
            default: return mime.substring("audio/".length());
        }
    }
}
