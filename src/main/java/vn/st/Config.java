package vn.st;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cau hinh, luu tai ~/.screen-translator/config.json. API key duoc ma hoa bang Windows DPAPI. */
public class Config {
    public String pythonExe = "";
    public int port = 8765;
    public int scanIntervalMs = 100;
    public double fontScale = 1.0;
    public int bgOpacity = 255;               // 0..255
    public String textColor = "auto";         // "auto" hoac "#RRGGBB"
    public int cfgVersion = 0;                // 3 = mac dinh dich offline bang tu dien; 4 = chu ky quet 100 ms + ngon ngu nguon
    public String srcLang = "zh";             // ngon ngu tren man hinh can dich: zh | en | ja | ko | ru | latin
    public int detMax = 0;                    // canh dai toi da cua anh phat hien chu (0 = tu dong); tang len neu chu qua nho bi bo sot
    public String provider = "offline";       // offline | auto | google | openai
    public String openaiUrl = "http://localhost:11434/v1";
    public String openaiModel = "qwen2.5:7b";
    public String openaiKeyEnc = "";
    public String mode = "speed";             // speed | accuracy
    public boolean lowEnd = false;
    public int cpuMode = 0;                   // 0 = can bang (mac dinh), 1 = tiet kiem CPU, 2 = toi da toc do
    public boolean mergeLines = true;
    public boolean saveHistory = false;
    public boolean autoTranslate = true;
    public boolean hideOverlayWhileCapture = false;
    public double minConfidence = 0.5;
    public String hkToggle = "F6";
    public String hkPause = "F7";
    public String hkReselect = "F8";
    public String hkStop = "F9";
    public String hkFull = "F10";             // dich toan bo man hinh (bam lan nua de dung)
    public int[] lastRegion = null;           // {x, y, w, h} vung dich gan nhat

    /** Ngon ngu nguon ho tro: {ma, ten hien thi}. Ma khop voi python/ocrengine.py (LANGS). */
    public static final String[][] LANGS = {
            {"zh", "Tiếng Trung"}, {"en", "Tiếng Anh"}, {"ja", "Tiếng Nhật"}, {"ko", "Tiếng Hàn"},
            {"ru", "Tiếng Nga"}, {"latin", "Chữ Latin (Pháp/Đức/Tây Ban Nha/Bồ/Ý/Indo…)"}};

    public static int langIndex(String code) {
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i][0].equals(code)) return i;
        return 0;
    }

    public String langName() {
        return LANGS[langIndex(srcLang)][1];
    }

    public static Path dir() {
        return Paths.get(System.getProperty("user.home"), ".screen-translator");
    }

    private static Path file() {
        return dir().resolve("config.json");
    }

    public static Config load() {
        try {
            if (Files.exists(file())) {
                Config c = new Gson().fromJson(Files.readString(file(), StandardCharsets.UTF_8), Config.class);
                if (c != null) {
                    boolean dirty = false;
                    if (c.cfgVersion < 3) {            // google/auto cu hay bi chan 429 -> chuyen sang dich offline
                        if ("google".equals(c.provider) || "auto".equals(c.provider)) c.provider = "offline";
                        c.cfgVersion = 3;
                        dirty = true;
                    }
                    if (c.cfgVersion < 4) {            // chu ky quet mac dinh cu (250 ms) -> 100 ms; gia tri nguoi dung tu dat giu nguyen
                        if (c.scanIntervalMs == 250) c.scanIntervalMs = 100;
                        c.cfgVersion = 4;
                        dirty = true;
                    }
                    if (c.srcLang == null || c.srcLang.isBlank()) c.srcLang = "zh";
                    if (dirty) c.save();
                    return c;
                }
            }
        } catch (Exception e) {
            AppLog.error("Doc config that bai", e);
        }
        Config c = new Config();
        c.cfgVersion = 4;
        return c;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(dir());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getApiKey() {
        if (openaiKeyEnc == null || openaiKeyEnc.isEmpty()) return "";
        try {
            if (openaiKeyEnc.startsWith("dpapi:"))
                return new String(Win32.unprotect(Base64.getDecoder().decode(openaiKeyEnc.substring(6))), StandardCharsets.UTF_8);
            if (openaiKeyEnc.startsWith("plain:"))
                return new String(Base64.getDecoder().decode(openaiKeyEnc.substring(6)), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // khong giai ma duoc (vi du khac tai khoan Windows) -> coi nhu chua co key
        }
        return "";
    }

    public void setApiKey(String key) {
        if (key == null || key.isEmpty()) {
            openaiKeyEnc = "";
            return;
        }
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        try {
            if (!Win32.isWindows()) throw new IllegalStateException();
            openaiKeyEnc = "dpapi:" + Base64.getEncoder().encodeToString(Win32.protect(raw));
        } catch (Throwable t) {
            openaiKeyEnc = "plain:" + Base64.getEncoder().encodeToString(raw);
        }
    }

    /** Cau hinh gui cho backend Python. */
    public Map<String, Object> toBackend() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("openaiUrl", openaiUrl);
        m.put("openaiKey", getApiKey());
        m.put("openaiModel", openaiModel);
        m.put("mode", mode);
        m.put("lowEnd", lowEnd);
        m.put("mergeLines", mergeLines);
        m.put("minConfidence", minConfidence);
        m.put("saveHistory", saveHistory);
        m.put("srcLang", srcLang);
        m.put("detMax", detMax);
        m.put("ocrThreads", ocrThreads());
        return m;
    }

    /** So luong luong OCR theo muc dung CPU: 0 = tu dong (toi da 4), 2 = tiet kiem, -1 = toan bo nhan. */
    public int ocrThreads() {
        return cpuMode == 1 ? 2 : cpuMode == 2 ? -1 : 0;
    }

    /** Ty le thoi gian toi da cho OCR trong cua so 10 s (1.0 = khong gioi han). */
    public double ocrBudget() {
        return cpuMode == 1 ? 0.25 : cpuMode == 2 ? 1.0 : 0.5;
    }

    /** He so nghi giua cac lan OCR "nong" (vung doi lien tuc): nghi = thoi gian OCR x he so. */
    public double coolFactor() {
        return cpuMode == 1 ? 3.0 : cpuMode == 2 ? 0.0 : 1.0;
    }
}
