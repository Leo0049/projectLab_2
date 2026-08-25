package com.example.demo.controller;

import com.example.demo.service.ImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通用圖片上傳。
 *
 * <p>⚠️ M-1 修復：舊版只檢查「檔案非空」，副檔名原樣保留、內容完全不驗——
 * 任何登入者都能上傳 .html／.svg（內含 script）落到 /uploads/**（permitAll），
 * 形成<b>儲存型 XSS</b>：受害者瀏覽同源頁面即可被竊取 localStorage 裡的 JWT。
 * 另外路徑寫死 src/main/resources/static/uploads/，與 ImageStorageService 的
 * uploads/ 目錄雙軌並行，行為不一致。
 *
 * <p>修復後：
 * <ul>
 *   <li>副檔名白名單＋<b>magic bytes</b> 驗真實內容（副檔名可偽造，內容不會）</li>
 *   <li>大小上限 5MB</li>
 *   <li>落地檔名一律 UUID，儲存統一走 {@link ImageStorageService}
 *       （未設定 Cloudinary 時存本機 uploads/，由 WebConfig 以 /uploads/** 提供）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/upload")
@Slf4j
@RequiredArgsConstructor
public class UploadController {

    private final ImageStorageService imageStorageService;

    /** 允許的圖片副檔名 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /** 單檔上限：與頭像/logo 的實際用量相比已足夠寬鬆 */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    @PostMapping
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "請選擇要上傳的檔案"));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body(Map.of("error", "檔案不可超過 5MB"));
        }

        String originalFilename = file.getOriginalFilename();
        String extension = extensionOf(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return ResponseEntity.badRequest().body(Map.of("error", "僅允許上傳圖片（png/jpg/jpeg/gif/webp）"));
        }

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            log.warn("上傳讀取失敗: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "讀取檔案失敗"));
        }
        if (!looksLikeImage(data)) {
            // ⚠️ 副檔名可偽造；內容開頭的 magic bytes 才是身分證
            return ResponseEntity.badRequest().body(Map.of("error", "檔案內容不是有效的圖片"));
        }

        try {
            String url = imageStorageService.upload(data, "files", null, originalFilename);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (IOException e) {
            log.error("圖片儲存失敗", e);
            return ResponseEntity.status(500).body(Map.of("error", "上傳失敗，請稍後再試"));
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 以檔頭 bytes 判斷是否為白名單內的圖片格式 */
    private static boolean looksLikeImage(byte[] d) {
        if (d == null || d.length < 12) {
            return false;
        }
        // JPEG: FF D8 FF
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return true;
        }
        // GIF: GIF87a / GIF89a
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8'
                && (d[4] == '7' || d[4] == '9') && d[5] == 'a') {
            return true;
        }
        // WebP: RIFF....WEBP
        return d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
    }
}
