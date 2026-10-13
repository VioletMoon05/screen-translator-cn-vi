## Bản nâng cấp phản hồi nhanh

- Chu kỳ quét mặc định giảm từ **250 ms xuống 100 ms** để phát hiện cuộn/lia chuột nhanh hơn (có thể làm CPU tăng nhẹ).
- Thời gian chờ gom thay đổi giảm từ **1.500 ms xuống 180 ms**, giúp gửi vùng ảnh đi OCR sớm hơn khi nội dung ổn định.
- Bổ sung **366 mục từ vựng giản thể Trung–Việt** về game, giao diện, khám phá, chiến đấu, vật phẩm và thông báo hệ thống vào `python/dict_zh_vi.tsv`. Từ điển người dùng vẫn được ưu tiên.
- Đây là cải thiện độ trễ ở phía ứng dụng; thời gian OCR thực tế còn phụ thuộc độ phân giải vùng quét, CPU và độ phức tạp ảnh. Câu dài vẫn có thể cần dịch vụ LLM để tự nhiên hơn.

Lưu ý: cấu hình đã lưu tại `%USERPROFILE%\\.screen-translator\\config.json` có thể giữ `scanIntervalMs` cũ. Muốn áp dụng chu kỳ nhanh, mở file đó và đặt `scanIntervalMs` thành `100` (hoặc `120` nếu CPU yếu), rồi khởi động lại ứng dụng.

---

# Screen Translator CN-VI (Java + Python)

Nhận diện chữ Trung (giản thể/phồn thể) trong một vùng màn hình, dịch sang tiếng Việt và **vẽ bản dịch đè lên đúng vị trí chữ gốc**; tự cập nhật khi cuộn trang và tự xóa bản dịch khi chữ gốc biến mất.

## Phân chia Java / Python

| Phần | Ngôn ngữ | Vì sao |
|---|---|---|
| Giao diện, chọn vùng, overlay trong suốt click-through, phím tắt toàn cục, chụp màn hình, phát hiện thay đổi, đồng bộ trạng thái | **Java 17 (Swing + JNA)** | Điều khiển cửa sổ Windows ổn định, DPI, nhiều màn hình |
| OCR, gộp dòng thành đoạn, dịch, phân tích nền/màu chữ, inpaint | **Python 3.11 (RapidOCR/ONNX, OpenCV)** | Hệ sinh thái OCR/xử lý ảnh |

Java khởi chạy Python như tiến trình con và nói chuyện qua HTTP **chỉ trên 127.0.0.1**. Khi Java thoát (kể cả bị kill) Python tự thoát.

```
screen-translator-cn-vi/
├─ pom.xml                      Maven (Java 17, JNA, Gson, shade -> 1 file jar)
├─ setup.bat / run.bat          cài đặt + chạy
├─ build_backend_exe.bat        đóng gói backend Python thành exe (PyInstaller)
├─ package_app.bat              đóng gói cả ứng dụng (jpackage + PyInstaller)
├─ python/
│  ├─ server.py                 OCR + dịch + inpaint (HTTP server)
│  ├─ selftest.py               kiểm thử backend bằng ảnh chữ Trung tự tạo
│  └─ requirements.txt
└─ src/main/java/vn/st/
   ├─ Main, MainWindow          cửa sổ điều khiển
   ├─ RegionSelector            chọn vùng bằng kéo chuột (nhiều màn hình)
   ├─ OverlayWindow             overlay trong suốt, không nhận chuột, tự loại khỏi ảnh chụp
   ├─ Pipeline                  chụp -> phát hiện thay đổi -> OCR/dịch -> cập nhật overlay
   ├─ PythonBackend             quản lý tiến trình Python + HTTP
   ├─ HotkeyManager, Win32      phím tắt toàn cục, API Windows qua JNA (không cần jna-platform)
   ├─ SettingsDialog, Config    cài đặt (API key mã hóa bằng Windows DPAPI)
   └─ Item                      một khối bản dịch
```

## Cài đặt (Windows 10 2004+ / 11)

1. **Python 3.11** (python.org, tick *Add python.exe to PATH*).
2. **JDK 17+** (ví dụ Temurin) và **Maven** (`mvn -v` phải chạy được). JAVA_HOME trỏ tới JDK.
3. Mở CMD tại thư mục dự án:

```bat
setup.bat
run.bat
```

`setup.bat` tạo venv, cài thư viện, chạy `selftest.py` (kiểm tra OCR) rồi build jar. Lần chạy đầu cần mạng để pip tải thư viện; sau đó OCR chạy hoàn toàn cục bộ (model đã nằm trong gói `rapidocr-onnxruntime`).

Chạy tay (không dùng bat):
```bat
python -m venv python\.venv
python\.venv\Scripts\pip install -r python\requirements.txt
mvn -q -DskipTests package
java -jar target\screen-translator-1.0.0.jar
```

## Cách dùng

1. Mở ứng dụng, đợi dòng "Sẵn sàng" (backend nạp OCR).
2. **1. Chọn vùng** → màn hình mờ nhẹ → giữ chuột trái, kéo, thả. `Esc`/chuột phải để hủy. Vùng được nhớ cho lần chạy sau.
3. **2. Bắt đầu dịch** → cửa sổ điều khiển tự thu nhỏ và app **dịch ngay** vùng đó, rồi **dịch liên tục** (tự cập nhật khi cuộn/đổi nội dung) cho đến khi bạn bấm **Dừng dịch** hoặc phím `F9`. (Bấm "Bắt đầu dịch" khi chưa có vùng thì app mở bước chọn vùng rồi dịch luôn.)

Phía trên vùng dịch có một thanh trạng thái nhỏ: *Đang quét…*, *Đang dịch • N khối*, *Tạm dừng* hoặc *Lỗi: …* (xanh = ổn, cam = cảnh báo, đỏ = lỗi) cùng viền xanh đứt nét quanh vùng. Nếu thấy "chưa thấy chữ Trung" nghĩa là app đang chạy nhưng OCR chưa nhận ra chữ Trung trong vùng.

Phím tắt toàn cục (đổi được trong Cài đặt, đủ F1–F12): `F6` bật/tắt overlay · `F7` tạm dừng/tiếp tục · `F8` chọn lại vùng (chọn xong dịch luôn) · `F9` dừng dịch và mở lại cửa sổ điều khiển. Nếu phím trùng ứng dụng khác, cửa sổ sẽ báo đỏ — hãy đổi phím.

Chế độ "Tự động dịch" tắt → chỉ dịch khi bấm **Dịch ngay**.

**Tự phục hồi:** nếu OCR/dịch lỗi (mất mạng, Google chặn, backend chậm), app tự thử lại sau 2 → 4 → 8 → 15 giây ngay cả khi màn hình đứng yên; cứ 12 giây quét lại toàn vùng một lần để bắt các thay đổi nhỏ; nếu tiến trình Python chết, cửa sổ điều khiển tự khởi động lại nó. Mọi lỗi được ghi vào `%USERPROFILE%\.screen-translator\app.log` (nút **Mở file log**).

## Dịch thuật offline bằng từ điển lớn (không cần Google)

Mặc định app dịch **hoàn toàn offline**, không gọi mạng nên không còn lỗi TooManyRequests.

**Từ điển** (ưu tiên từ cao xuống thấp; khớp **cụm dài nhất** trước):
1. `%USERPROFILE%\.screen-translator\glossary.tsv` — từ của riêng bạn (`chữ Trung<TAB>tiếng Việt`, mỗi dòng một mục; ví dụ tên nhân vật, kỹ năng game). Sửa file là có hiệu lực ngay, không cần khởi động lại.
2. `python/dict_zh_vi.tsv` — từ điển chọn lọc đi kèm (~400 mục: nhãn giao diện, game, web, từ thông dụng, viết tiếng Việt hiện đại).
3. `%USERPROFILE%\.screen-translator\dict_big.db` — **từ điển lớn ~1,19 triệu cụm** + 90 nghìn tên riêng + Hán Việt từng chữ + 219 luật đảo trật tự. **Tự tải khi mở app lần đầu** nếu chưa có (cần mạng một lần, ~45 MB tải về, ~46 MB trên đĩa; trạng thái hiện trong cửa sổ điều khiển và bản dịch tự cập nhật khi xong). Cũng có thể chạy tay `build_dict.bat` hoặc để `setup.bat` làm. Dịch một câu mất khoảng 1–5 ms.

**Lỗi ô vuông □ trên bản dịch:** do font overlay không có glyph tiếng Trung khi còn chữ Trung chưa dịch được. Đã sửa: overlay tự chọn font có đủ glyph (Segoe UI → Microsoft YaHei UI → font logic Dialog), nên chữ Trung sót lại (nếu có) hiển thị đúng thay vì □.

Chữ phồn thể được đổi sang giản thể để tra. Từ lạ được đọc theo âm Hán Việt (2–4 chữ liền nhau → viết hoa như tên riêng). Có thêm xử lý "我的书 → sách của tôi" và đổi đại từ cổ của VietPhrase (ngươi → bạn, ta → tôi…). Mở app sẽ hiện "Từ điển offline: N cụm"; nếu thấy cảnh báo "Chưa có từ điển lớn" nghĩa là app đang tải, hoặc tải lỗi do không có mạng — khi đó dùng `build_dict.bat --from-dir <thư mục>` với 4 file `Vietphrase.txt, Names.txt, HanViet.txt, ZZLuatNhan.txt`.

**Nguồn dữ liệu & giấy phép:** từ điển lớn lấy từ bộ VietPhrase do cộng đồng QuickTranslator chia sẻ (repo công khai `hoangtuantk/Vietphrase` trên GitHub). Bộ này **không có giấy phép rõ ràng**, nên mình không đóng gói sẵn mà để máy bạn tự tải về dùng cá nhân; đừng phát hành lại. Từ điển chọn lọc và công cụ là của dự án này.

**Chất lượng — nói thẳng:** kiểu dịch của VietPhrase/QuickTranslator là khớp cụm theo trật tự tiếng Trung, đọc hiểu được nhưng chưa trôi chảy ("Sách của tôi nhìn rất đẹp", "Mạng kết nối thất bại, xin kiểm tra mạng cài đặt…"). Tốt nhất với nhãn/nút/menu/thông báo hệ thống/hội thoại game; câu văn dài và cấu trúc "A 的 B" còn sai trật tự từ. Gặp từ/tên dịch chưa ưng, thêm vào `glossary.tsv` là xong và sẽ luôn được ưu tiên.

**Các chế độ** (Cài đặt → "Dịch vụ"): `offline` (mặc định) · `auto` (offline, chỉ gọi Google khi độ phủ từ điển thấp; gộp 1 request, cách quãng, bị 429 thì tự nghỉ) · `google` · `openai` (Ollama/LM Studio/OpenAI để dịch câu dài tự nhiên hơn). Bản dịch online được lưu cache `cache.db` để lần sau không gọi lại.

**Nhanh hơn:** vùng ảnh không đổi (quét lại định kỳ, thử lại sau lỗi) dùng lại kết quả OCR, không OCR lại.

## Cách hệ thống tránh dịch sai vị trí

- **Overlay tự loại khỏi ảnh chụp** (`SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)`), nên OCR luôn thấy chữ gốc. Nếu Windows quá cũ hoặc việc này không hiệu lực, ứng dụng tự chuyển sang *ẩn overlay trong tích tắc lúc chụp* (có thể hơi nhấp nháy). Cài đặt → "Ẩn overlay khi chụp" ép dùng cách này.
- **Phát hiện thay đổi** theo lưới ô 40px (lấy mẫu 8×8 điểm/ô). Khi một ô đổi: các bản dịch giao với vùng đổi bị gỡ ngay; chờ màn hình **ổn định một nhịp quét** rồi mới OCR *đúng vùng đã đổi* (mở rộng 2 ô). Màn hình đổi liên tục (video) vẫn được quét sau tối đa 1,5 giây.
- **Số thứ tự thay đổi**: mỗi kết quả OCR mang số thứ tự lúc gửi; nếu sau đó vùng đó lại đổi thì kết quả bị bỏ → kết quả chậm không ghi đè kết quả mới. Dòng bị cắt ở mép vùng quét bị loại, bản dịch cũ của dòng nguyên vẹn được giữ.
- **Giữ bố cục**: dòng liền nhau, thẳng lề, cùng cỡ chữ được gộp thành đoạn để dịch đúng ngữ cảnh rồi vẽ trong khung bao của đoạn; tự co cỡ chữ (≥ 9px) và xuống dòng; nếu vẫn dài thì khung nền nới ra thay vì cắt chữ.
- **Xóa chữ gốc**: nền đơn sắc → tô bằng màu trung vị của viền quanh chữ; nền phức tạp (độ lệch chuẩn viền cao) → `cv2.inpaint` (Telea) trên mặt nạ nét chữ rồi phủ lớp mờ nhẹ cho dễ đọc; màu chữ lấy từ chữ gốc, tự đổi đen/trắng nếu độ tương phản thấp.

## Đóng gói thành .exe

```bat
package_app.bat
```
Chạy `mvn package`, PyInstaller đóng gói backend (`backend\server\server.exe`), `jpackage` tạo `dist\ScreenTranslatorCNVI\ScreenTranslatorCNVI.exe` kèm JRE. Java tự dùng `backend\server\server.exe` nếu có, nếu không dùng `python\server.py`. Chỉ đóng backend: `build_backend_exe.bat`. Gói PyInstaller với onnxruntime khá nặng (hàng trăm MB).

## Xử lý lỗi thường gặp

| Hiện tượng | Cách khắc phục |
|---|---|
| Bấm "Bắt đầu dịch" mà không thấy bản dịch | Nhìn thanh trạng thái phía trên vùng dịch và dòng đỏ trong cửa sổ điều khiển; bấm **Mở file log** và gửi lại vài dòng cuối nếu vẫn lỗi. |
| "Backend Python không khởi động được" | Chạy `python\.venv\Scripts\python.exe python\selftest.py` xem lỗi thật; chạy lại `setup.bat`. Cổng 8765 bận → đổi `port` trong `config.json`. |
| Lần đầu rất lâu | Nạp model ONNX; chờ dòng "Sẵn sàng". |
| Bản dịch nhấp nháy / OCR đọc lại tiếng Việt | Bật "Ẩn overlay khi chụp" trong Cài đặt; cập nhật Windows ≥ 10 bản 2004. |
| Overlay không hiện trên game | Game ở chế độ **fullscreen độc quyền** không cho cửa sổ khác đè lên. Đổi sang *Borderless/Windowed fullscreen*. |
| Phím F6/F7/F8 báo trùng | Đổi phím trong Cài đặt. |
| "Dịch lỗi: …" | Mất mạng hoặc Google chặn → chuyển sang Ollama cục bộ; kiểm tra URL/model/key. |
| Chữ nhỏ/mờ không nhận ra | Cài đặt → Ưu tiên `accuracy` (phóng to 1,5× trước OCR), hạ "Độ tin cậy OCR tối thiểu", hoặc chọn vùng nhỏ hơn. |
| Máy yếu / CPU cao | Bật "Chế độ nhẹ", tăng "Tốc độ quét (ms)", chọn vùng nhỏ. |
| Lệch vị trí khi nhiều màn hình khác DPI | Giới hạn đã biết (bên dưới); đặt vùng dịch trong một màn hình. |

## Đã kiểm thử gì — và chưa kiểm thử gì

Môi trường làm việc của tôi là Linux, **không phải Windows**, nên cần nói rõ:

**Đã chạy thật (Linux + màn hình ảo Xvfb):**
- `selftest.py`: OCR chữ giản thể, phồn thể, dòng lẫn Trung–Anh, chữ sáng trên nền tối, nền nhiều màu (kích hoạt inpaint). Backend nhận đúng chữ nhưng OCR vẫn có thể sai chữ, ví dụ 1 ký tự phồn thể bị đọc nhầm trong thử nghiệm của tôi.
- Luồng Java end-to-end (chụp → phát hiện thay đổi → HTTP → OCR → overlay) với một cửa sổ hiển thị chữ Trung: dịch lần đầu đúng; đổi toàn bộ nội dung (mô phỏng cuộn) thì bản dịch cũ bị gỡ và bản mới xuất hiện; khi chữ gốc biến mất thì overlay trống.
- Vẽ overlay tiếng Việt dài (xuống dòng, co chữ, nền phức tạp) ra ảnh để kiểm tra bằng mắt.

**Bản cập nhật luồng "chọn vùng → bắt đầu → dịch liên tục" (kiểm trên Linux + Xvfb, backend OCR thật, dịch giả lập):** dịch ngay ở khung đầu tiên; lỗi OCR/dịch giả lập 2 lần liên tiếp trên màn hình đứng yên → tự thử lại và ra bản dịch; đổi nội dung → bản dịch mới; chữ Trung biến mất → overlay trống. Mã Java biên dịch sạch (JNA/Gson chỉ kiểm bằng stub, chưa qua `mvn`).

**Biên dịch được nhưng CHƯA chạy trên Windows:** click-through qua JNA, `WDA_EXCLUDEFROMCAPTURE`, phím tắt `RegisterHotKey`, DPAPI. Đây là phần có rủi ro nhất; nếu gặp lỗi hãy gửi lại thông báo cụ thể để chỉnh.

**Chưa kiểm:** các ca 7–10 và 13 trong danh sách bạn nêu (kéo cửa sổ, đổi độ phân giải/DPI, đổi màn hình, ngắt mạng giữa chừng, game toàn màn hình). Hãy tự thử theo danh sách đó; mã đã có xử lý lỗi mạng (hiện lỗi đỏ, không xóa overlay cũ) nhưng tôi chưa xác nhận trên thực tế.

Tốc độ: trong môi trường thử của tôi, mỗi lần OCR cỡ 1–2 giây trên CPU của sandbox — **không đại diện** cho máy bạn. Thanh trạng thái hiển thị thời gian OCR/dịch thật; hãy dùng số đó thay vì tin con số của tôi.

## Giới hạn đã biết

- Chữ dọc (xếp theo cột) và chữ xoay chưa được xử lý riêng.
- Dịch từng đoạn, không có ngữ cảnh giữa các trang; nên tên riêng có thể dịch không nhất quán (model LLM cục bộ giúp ích phần này).
- Gộp đoạn dựa trên heuristic hình học; giao diện có nhiều mục sát nhau có thể bị gộp nhầm → tắt "Gộp các dòng liền nhau".
- Chọn vùng và overlay dùng tọa độ logic của Java; nhiều màn hình có DPI khác nhau có thể lệch.
- Cập nhật khi cuộn là "xóa → chờ ổn định → quét lại", nên có độ trễ ngắn sau khi dừng cuộn thay vì dịch mượt trong lúc cuộn.
- Lịch sử SQLite chỉ ghi khi bật trong Cài đặt; mặc định không lưu gì.
