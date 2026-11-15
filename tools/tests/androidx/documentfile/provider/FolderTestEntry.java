package androidx.documentfile.provider;
import android.net.Uri;
public class FolderTestEntry extends DocumentFile {
        final String name;
        final boolean directory;
        final FolderTestEntry[] children;
        public FolderTestEntry(String name, boolean directory, FolderTestEntry... children) {
            super(null); this.name = name; this.directory = directory; this.children = children;
        }
        public Uri getUri() { return Uri.parse("content://opaque/document/" + System.identityHashCode(this)); }
        public String getName() { return name; }
        public String getType() { return directory ? null : "audio/flac"; }
        public boolean isDirectory() { return directory; }
        public boolean isFile() { return !directory; }
        public boolean isVirtual() { return false; }
        public long lastModified() { return 0; }
        public long length() { return 0; }
        public boolean canRead() { return true; }
        public boolean canWrite() { return false; }
        public boolean delete() { return false; }
        public boolean exists() { return true; }
        public DocumentFile[] listFiles() { return children; }
        public DocumentFile createFile(String mime, String name) { return null; }
        public DocumentFile createDirectory(String name) { return null; }
        public boolean renameTo(String name) { return false; }
    }
