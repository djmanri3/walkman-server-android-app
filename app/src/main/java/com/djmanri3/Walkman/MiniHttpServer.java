package com.djmanri3.Walkman;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mini servidor HTTP embebido (sin dependencias externas) que sirve los
 * archivos de audio seleccionados a la WebView a través de
 * {@code http://127.0.0.1:puerto/<id>}. El reproductor &lt;audio&gt; de HTML5
 * sólo acepta URLs http(s) (no file://), de ahí este servidor.
 *
 * <p>Soporta peticiones Range (necesario para el streaming y el seek del
 * &lt;audio&gt;) y el Content-Type correcto según la extensión.</p>
 */
public class MiniHttpServer {

    private static final String TAG = "MiniHttpServer";

    private final Context mContext;
    private final Map<String, Uri> mFiles = new ConcurrentHashMap<>();
    private final Map<String, byte[]> mBytes = new ConcurrentHashMap<>();
    private ServerSocket mServerSocket;
    private ExecutorService mPool;
    private Thread mAcceptThread;
    private volatile boolean mRunning = false;
    private int mPort = -1;

    public MiniHttpServer(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Registra un archivo (por URI de contenido) y devuelve su id de ruta. */
    public String addFile(String id, Uri uri) {
        mFiles.put(id, uri);
        return id;
    }

    /** Registra un bloque de bytes (p. ej. una carátula incrustada) en memoria. */
    public String addBytes(String id, byte[] data) {
        mBytes.put(id, data);
        return id;
    }

    public synchronized boolean start() {
        if (mRunning) {
            return true;
        }
        try {
            mServerSocket = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
            mPort = mServerSocket.getLocalPort();
            mPool = Executors.newCachedThreadPool();
            mRunning = true;
            mAcceptThread = new Thread(this::acceptLoop, "http-accept");
            mAcceptThread.setDaemon(true);
            mAcceptThread.start();
            Log.i(TAG, "Servidor iniciado en 127.0.0.1:" + mPort);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "No se pudo iniciar el servidor", e);
            return false;
        }
    }

    public int getPort() {
        return mPort;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + mPort + "/";
    }

    private void acceptLoop() {
        while (mRunning) {
            try {
                Socket s = mServerSocket.accept();
                mPool.execute(() -> handle(s));
            } catch (IOException e) {
                if (mRunning) {
                    Log.d(TAG, "accept falló: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                socket.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            String id = path.startsWith("/") ? path.substring(1)
                    : path;
            int q = id.indexOf('?');
            if (q >= 0) {
                id = id.substring(0, q);
            }

            // Preflight CORS (necesario para que las carátulas/audio http del
            // loopback se sirvan desde una página https).
            if ("OPTIONS".equals(method)) {
                String resp = "HTTP/1.1 200 OK\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: *\r\n"
                        + "Access-Control-Max-Age: 86400\r\n"
                        + "Content-Length: 0\r\n\r\n";
                out.write(resp.getBytes("UTF-8"));
                out.flush();
                socket.close();
                return;
            }

            if (!"GET".equals(method)) {
                respondStatus(out, 405, "Method Not Allowed");
                socket.close();
                return;
            }

            Uri uri = mFiles.get(id);
            byte[] blob = mBytes.get(id);
            if (uri == null && blob == null) {
                respondStatus(out, 404, "Not Found");
                socket.close();
                return;
            }

            String name = uri != null
                    ? (uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "audio")
                    : "cover.jpg";
            String mime = mimeFor(name);
            long length = -1;
            if (blob != null) {
                length = blob.length;
            } else {
                try {
                    if ("content".equals(uri.getScheme())) {
                        length = mContext.getContentResolver()
                                .openAssetFileDescriptor(uri, "r").getLength();
                    }
                } catch (Exception ignored) {
                }
            }

            long rangeStart = 0;
            long rangeEnd = length >= 0 ? length - 1 : -1;
            boolean isRange = false;
            String rangeHeader = readHeader(in, "Range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                isRange = true;
                String spec = rangeHeader.substring(6);
                String[] rp = spec.split("-");
                try {
                    rangeStart = Long.parseLong(rp[0]);
                    if (rp.length > 1 && !rp[1].isEmpty()) {
                        rangeEnd = Long.parseLong(rp[1]);
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            StringBuilder head = new StringBuilder();
            if (isRange && length >= 0) {
                if (rangeEnd >= length) {
                    rangeEnd = length - 1;
                }
                long len = rangeEnd - rangeStart + 1;
                head.append("HTTP/1.1 206 Partial Content\r\n");
                head.append("Access-Control-Allow-Origin: *\r\n");
                head.append("Content-Range: bytes ").append(rangeStart).append("-")
                        .append(rangeEnd).append("/").append(length).append("\r\n");
                head.append("Accept-Ranges: bytes\r\n");
                head.append("Content-Length: ").append(len).append("\r\n");
            } else {
                head.append("HTTP/1.1 200 OK\r\n");
                head.append("Access-Control-Allow-Origin: *\r\n");
                head.append("Accept-Ranges: bytes\r\n");
                if (length >= 0) {
                    head.append("Content-Length: ").append(length).append("\r\n");
                } else {
                    head.append("Transfer-Encoding: chunked\r\n");
                }
            }
            head.append("Content-Type: ").append(mime).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes("UTF-8"));
            out.flush();

            InputStream content;
            if (blob != null) {
                content = new java.io.ByteArrayInputStream(blob);
            } else {
                content = mContext.getContentResolver().openInputStream(uri);
            }
            if (content == null) {
                socket.close();
                return;
            }
            try {
                if (rangeStart > 0) {
                    long skip = rangeStart;
                    while (skip > 0) {
                        long s = content.skip(skip);
                        if (s <= 0) {
                            break;
                        }
                        skip -= s;
                    }
                }
                long remaining = isRange && length >= 0 ? (rangeEnd - rangeStart + 1) : -1;
                byte[] buf = new byte[8192];
                int n;
                while ((n = content.read(buf)) != -1) {
                    if (remaining >= 0) {
                        if (n > remaining) {
                            n = (int) remaining;
                        }
                        remaining -= n;
                    }
                    out.write(buf, 0, n);
                    if (remaining == 0) {
                        break;
                    }
                }
            } finally {
                content.close();
            }
            socket.close();
        } catch (Exception e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String readHeader(InputStream in, String name) throws IOException {
        for (;;) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                return null;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
    }

    private void respondStatus(OutputStream out, int code, String msg) throws IOException {
        String body = msg;
        String resp = "HTTP/1.1 " + code + " " + msg + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.getBytes("UTF-8").length + "\r\n"
                + "Connection: close\r\n\r\n" + body;
        out.write(resp.getBytes("UTF-8"));
        out.flush();
    }

    private static String mimeFor(String name) {
        String ext = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase()
                : "";
        switch (ext) {
            case "mp3": return "audio/mpeg";
            case "flac": return "audio/flac";
            case "ogg":
            case "oga": return "audio/ogg";
            case "opus": return "audio/opus";
            case "m4a":
            case "aac": return "audio/mp4";
            case "wav": return "audio/wav";
            case "webm": return "audio/webm";
            case "wma": return "audio/x-ms-wma";
            case "m3u": return "audio/x-mpegurl";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            default: return "application/octet-stream";
        }
    }

    public synchronized void stop() {
        mRunning = false;
        try {
            if (mServerSocket != null) {
                mServerSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (mPool != null) {
            mPool.shutdownNow();
        }
    }

    static {
        // nada
    }
}
