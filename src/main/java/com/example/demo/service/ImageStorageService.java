package com.example.demo.service;

import com.cloudinary.Cloudinary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 圖片儲存。有設定 Cloudinary 憑證就上傳到 Cloudinary，沒有就存到本機並回傳
 * 可直接存取的相對路徑（由 WebConfig 的 /uploads/** 對應）。
 *
 * 這樣做的理由：Cloudinary 沒有可供上傳的公開測試帳號，若上傳一律綁死 Cloudinary，
 * 任何人 clone 下來都得先自己申請帳號才能用到頭像、品牌 logo、門市封面與優惠券圖片，
 * 否則會收到函式庫拋出的 "cloud_name is disabled"。改為自動回退後，
 * 不需要任何第三方帳號也能完整展示。
 */
@Slf4j
@Service
public class ImageStorageService {

    @Value("${cloudinary.cloud-name:}")
    private String cloudName;

    @Value("${cloudinary.api-key:}")
    private String apiKey;

    @Value("${cloudinary.api-secret:}")
    private String apiSecret;

    /** 本機儲存位置，對外以 /uploads/** 提供 */
    @Value("${app.upload.local-dir:uploads}")
    private String localDir;

    private final Cloudinary cloudinary;

    public ImageStorageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    @PostConstruct
    void logMode() {
        if (cloudinaryEnabled()) {
            log.info("圖片儲存：Cloudinary（cloud_name={}）", cloudName);
        } else {
            log.info("圖片儲存：本機目錄 {}／（未設定 Cloudinary 憑證，功能仍可正常使用）", localDir);
        }
    }

    public boolean cloudinaryEnabled() {
        return notBlank(cloudName) && notBlank(apiKey) && notBlank(apiSecret);
    }

    /**
     * 上傳圖片。
     *
     * @param data     圖片位元組
     * @param folder   分類資料夾，例如 avatars / brands / stores / coupons
     * @param publicId 穩定識別碼（同一個 id 會覆寫舊檔），可為 null 則自動產生
     * @param filename 原始檔名，僅用來推副檔名，可為 null
     * @return 可直接放進 img src 的網址或路徑
     */
    public String upload(byte[] data, String folder, String publicId, String filename) throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("圖片內容為空");
        }
        return cloudinaryEnabled()
                ? uploadToCloudinary(data, folder, publicId)
                : uploadToLocal(data, folder, publicId, filename);
    }

    public String upload(MultipartFile file, String folder, String publicId) throws IOException {
        return upload(file.getBytes(), folder, publicId, file.getOriginalFilename());
    }

    private String uploadToCloudinary(byte[] data, String folder, String publicId) throws IOException {
        Map<String, Object> options = new HashMap<>();
        options.put("folder", folder);
        options.put("resource_type", "image");
        if (notBlank(publicId)) {
            options.put("public_id", publicId);
            options.put("overwrite", true);
        }
        Map<?, ?> result = cloudinary.uploader().upload(data, options);
        return String.valueOf(result.get("secure_url"));
    }

    private String uploadToLocal(byte[] data, String folder, String publicId, String filename) throws IOException {
        String ext = extensionOf(filename);
        String name = (notBlank(publicId) ? publicId : UUID.randomUUID().toString()) + ext;

        Path dir = Paths.get(localDir, folder);
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        Files.write(target, data);

        return "/uploads/" + folder + "/" + name;
    }

    private String extensionOf(String filename) {
        if (filename == null) return ".png";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return ".png";
        String ext = filename.substring(dot).toLowerCase();
        // 只允許圖片副檔名，避免以原始檔名決定落地檔案的型別
        return switch (ext) {
            case ".png", ".jpg", ".jpeg", ".gif", ".webp" -> ext;
            default -> ".png";
        };
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
