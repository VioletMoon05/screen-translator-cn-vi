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
    public int scanIntervalMs = 250;
    public double fontScale = 1.0;
    public int bgOpacity = 255;               // 0..255
    public String textColor = "auto";         // "auto" hoac "#RRGGBB"
    public int cfgVersion = 0;                // 3 = mac dinh dich offline bang tu dien
    public String provider = "offline";       // offline | auto | google | openai
    public String openaiUrl = "http://localhost:11434/v1";
    public String openaiModel = "qwen2.5:7b";
    public String openaiKeyEnc = "";
    public String mode = "speed";             // speed | accuracy
    public boolean lowEnd = false;
    public boolean mergeLines = true;
    public boolean saveHistory = false;
    public boolean autoTranslate = true;
    public boolean hideOverlayWhileCapture = false;
    public double minConfidence = 0.5;
    public String hkToggle = "F6";
    public String hkPause = "F7";
    public String hkReselect = "F8";
    public String hkStop = "F9";
    public int[] lastRegion = null;           // {x, y, w, h} vung dich gan nhat

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
                    if (c.cfgVersion < 3) {            // google/auto cu hay bi chan 429 -> chuyen sang dich offline
                        if ("google".equals(c.provider) || "auto".equals(c.provider)) c.provider = "offline";
                        c.cfgVersion = 3;
                        c.save();
                    }
                    return c;
                }
            }
        } catch (Exception e) {
            AppLog.error("Doc config that bai", e);
        }
        Config c = new Config();
        c.cfgVersion = 3;
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
        return m;
    }
}
