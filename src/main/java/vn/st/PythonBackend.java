package vn.st;

import com.google.gson.Gson;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/** Quan ly tien trinh Python (OCR + dich) va giao tiep HTTP noi bo 127.0.0.1. */
public class PythonBackend {
    public static class Row {
        public int x, y, w, h, lh;
        public String src, dst, patch;
        public int[] bg, fg;
        public boolean complex;
    }

    public static class Resp {
        public List<Row> items;
        public String error;
        public int ocr_ms, tr_ms, blocks;
    }

    private final Config cfg;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    private Process proc;
    private volatile String lastLog = "";
    private Consumer<String> logSink = s -> {};

    public PythonBackend(Config cfg) {
        this.cfg = cfg;
    }

    public synchronized boolean isAlive() {
        return proc != null && proc.isAlive();
    }

    public void setLogSink(Consumer<String> s) {
        this.logSink = s;
    }

    public String lastLog() {
        return lastLog;
    }

    private String base() {
        return "http://127.0.0.1:" + cfg.port;
    }

    /** Tim tep tuong doi quanh thu muc chay: cung thu muc jar, thu muc cha, thu muc lam viec. */
    static Path find(String rel) {
        List<Path> roots = new ArrayList<>();
        try {
            Path p = Paths.get(PythonBackend.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path d = Files.isDirectory(p) ? p : p.getParent();
            roots.add(d);
            if (d.getParent() != null) roots.add(d.getParent());
            if (d.getParent() != null && d.getParent().getParent() != null) roots.add(d.getParent().getParent());
        } catch (Exception ignored) {
        }
        roots.add(Paths.get(System.getProperty("user.dir")));
        for (Path r : roots) {
            Path c = r.resolve(rel);
            if (Files.exists(c)) return c;
        }
        return null;
    }

    public synchronized void start() throws Exception {
        stop();
        List<String> cmd = new ArrayList<>();
        Path packaged = find("backend/server/server.exe");   // ban dong goi bang PyInstaller
        if (packaged != null) {
            cmd.add(packaged.toString());
        } else {
            Path script = find("python/server.py");
            if (script == null) throw new IllegalStateException("Không tìm thấy python/server.py");
            String py = cfg.pythonExe;
            if (py == null || py.isBlank()) {
                Path venv = find("python/.venv/Scripts/python.exe");
                py = venv != null ? venv.toString() : "python";
            }
            cmd.add(py);
            cmd.add("-u");
            cmd.add(script.toString());
        }
        cmd.add("--port");
        cmd.add(String.valueOf(cfg.port));
        cmd.add("--data-dir");
        cmd.add(Config.dir().toString());
        cmd.add("--threads");
        cmd.add(String.valueOf(cfg.ocrThreads()));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        proc = pb.start();     // stdin de mo: Python tu thoat khi Java thoat
        Process p = proc;
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lastLog = line;
                    logSink.accept(line);
                    AppLog.info("[py] " + line);
                }
            } catch (Exception ignored) {
            }
        }, "py-log");
        t.setDaemon(true);
        t.start();
    }

    /** So muc tu dien lon dang nap o backend (0 = chua dung build_dict). -1 neu khong doc duoc. */
    public int bigDictSize() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/health"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"dict\": \\[(\\d+), (\\d+), (\\d+)\\]").matcher(r.body());
            return m.find() ? Integer.parseInt(m.group(3)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Yeu cau backend tu tai va dung tu dien lon (chay nen). */
    public void startDictBuild() throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(base() + "/build_dict"))
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** JSON {"state": idle|running|done|error, "msg": ..., "size": N}. */
    public String dictStatus() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/dict_status"))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    /** JSON {"state": ready, "msg": san sang}. */
    public String langStatus() throws Exception {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(base() + "/lang_status"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "{\"state\":\"ready\",\"msg\":\"sẵn sàng\"}";
        }
    }

    public boolean waitReady(int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (proc != null && !proc.isAlive()) return false;
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/health"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200 && r.body().contains("\"ready\": true")) return true;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    public void pushConfig() throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(base() + "/config"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(cfg.toBackend()), StandardCharsets.UTF_8)).build();
        http.send(rq, HttpResponse.BodyHandlers.discarding());
    }

    /** Gui anh PNG, nhan danh sach khoi da dich (toa do theo pixel cua anh gui di). */
    public Resp ocr(byte[] png) throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(base() + "/ocr"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(png)).build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Resp resp = gson.fromJson(r.body(), Resp.class);
        if (resp == null) resp = new Resp();
        if (resp.items == null) resp.items = new ArrayList<>();
        if (r.statusCode() != 200 && resp.error == null) resp.error = "Backend trả về HTTP " + r.statusCode();
        return resp;
    }

    public static Color rgb(int[] c, Color def) {
        return c != null && c.length >= 3 ? new Color(c[0], c[1], c[2]) : def;
    }

    public static BufferedImage decodePatch(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void stop() {
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
    }
}
