package com.ets.unpack.hub;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/** 极简本地 HTTP 服务（支持请求体上传、可指定响应类型）。 */
public final class HttpApi {

    public static final class Response {
        public String body = "";
        public String contentType = "application/json; charset=utf-8";
        public Response() {}
        public Response(String body, String contentType) { this.body = body == null ? "" : body; this.contentType = contentType; }
    }
    public interface Router { Response route(String uri, byte[] body); }

    private final int port;
    private final Router router;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;

    public HttpApi(int port, Router router) { this.port = port; this.router = router; }

    public synchronized String start() {
        if (running) return "already running on " + port;
        try {
            server = new ServerSocket(port);
            running = true;
            thread = new Thread(new Runnable() { public void run() { loop(); } }, "EtsHubHttp");
            thread.setDaemon(true);
            thread.start();
            return "listening on 0.0.0.0:" + port;
        } catch (Throwable t) {
            running = false;
            try { if (server != null) server.close(); } catch (Throwable ignored) {}
            server = null;
            return "start fail: " + t;
        }
    }
    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
        server = null;
    }
    public boolean isRunning() { return running; }
    public int port() { return port; }

    private void loop() {
        while (running) {
            Socket s = null;
            try { s = server.accept(); handle(s); }
            catch (Throwable t) { if (running) { try { Thread.sleep(30); } catch (Throwable ignored) {} } }
            finally { try { if (s != null) s.close(); } catch (Throwable ignored) {} }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String uri = "/";
            String[] parts = line.split(" ");
            if (parts.length > 1) uri = parts[1];
            int contentLength = 0;
            String hl;
            while ((hl = readLine(in)) != null && hl.length() > 0) {
                int c = hl.indexOf(':');
                if (c > 0) {
                    String k = hl.substring(0, c).trim();
                    String v = hl.substring(c + 1).trim();
                    if (k.equalsIgnoreCase("Content-Length")) { try { contentLength = Integer.parseInt(v); } catch (Throwable ignored) {} }
                }
            }
            byte[] body = new byte[0];
            if (contentLength > 0) {
                if (contentLength > 40 * 1024 * 1024) contentLength = 40 * 1024 * 1024;
                ByteArrayOutputStream bos = new ByteArrayOutputStream(contentLength);
                byte[] buf = new byte[65536];
                int remain = contentLength;
                while (remain > 0) {
                    int r = in.read(buf, 0, Math.min(buf.length, remain));
                    if (r < 0) break;
                    bos.write(buf, 0, r);
                    remain -= r;
                }
                body = bos.toByteArray();
            }
            Response resp = router.route(uri, body);
            if (resp == null) resp = new Response();
            byte[] b = resp.body.getBytes("UTF-8");
            String hdr = "HTTP/1.1 200 OK\r\nContent-Type: " + resp.contentType + "\r\n"
                    + "Content-Length: " + b.length + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
            OutputStream os = s.getOutputStream();
            os.write(hdr.getBytes("UTF-8"));
            os.write(b);
            os.flush();
        } catch (Throwable ignored) {}
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
        }
        if (c == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), "UTF-8");
    }
}
