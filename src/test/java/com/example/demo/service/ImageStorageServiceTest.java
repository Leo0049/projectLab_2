package com.example.demo.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 沒有 Cloudinary 憑證時，圖片上傳必須改走本機儲存而不是失敗。
 *
 * 這支測試存在的理由：先前所有上傳端點都直接呼叫 cloudinary.uploader()，
 * 憑證為空時會拋出函式庫的 "cloud_name is disabled"，等於任何人 clone 下來
 * 都得先自己申請 Cloudinary 帳號，頭像／品牌 logo／門市封面／優惠券圖片才能用。
 * Cloudinary 並沒有可供上傳的公開測試帳號，所以正解是讓「無憑證」成為可用路徑。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "cloudinary.cloud-name=",
        "cloudinary.api-key=",
        "cloudinary.api-secret=",
        "app.upload.local-dir=target/test-uploads"
})
class ImageStorageServiceTest {

    private static final Path TEST_DIR = Paths.get("target/test-uploads");

    @Autowired
    private ImageStorageService imageStorageService;

    @AfterEach
    void cleanUp() throws IOException {
        if (!Files.exists(TEST_DIR)) return;
        try (var paths = Files.walk(TEST_DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        }
    }

    @Test
    @DisplayName("無 Cloudinary 憑證時，判定為停用")
    void cloudinaryDisabledWhenCredentialsBlank() {
        assertFalse(imageStorageService.cloudinaryEnabled(),
                "憑證為空時不應判定為啟用，否則上傳會拋 cloud_name is disabled");
    }

    @Test
    @DisplayName("無憑證時上傳仍成功，檔案落在本機並回傳可用路徑")
    void uploadFallsBackToLocalStorage() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "avatar", "me.jpg", "image/jpeg", "fake-image-bytes".getBytes());

        String url = imageStorageService.upload(file, "avatars", "user_42");

        assertEquals("/uploads/avatars/user_42.jpg", url, "應回傳可直接放進 img src 的路徑");
        assertTrue(Files.exists(TEST_DIR.resolve("avatars").resolve("user_42.jpg")),
                "檔案應實際寫入本機目錄");
    }

    @Test
    @DisplayName("同一個 publicId 重複上傳會覆寫，不會累積垃圾檔")
    void sameePublicIdOverwrites() throws Exception {
        MockMultipartFile first = new MockMultipartFile("avatar", "a.png", "image/png", "one".getBytes());
        MockMultipartFile second = new MockMultipartFile("avatar", "b.png", "image/png", "two".getBytes());

        imageStorageService.upload(first, "avatars", "user_7");
        imageStorageService.upload(second, "avatars", "user_7");

        Path target = TEST_DIR.resolve("avatars").resolve("user_7.png");
        assertEquals("two", Files.readString(target), "第二次上傳應覆寫第一次的內容");
        try (var paths = Files.list(TEST_DIR.resolve("avatars"))) {
            assertEquals(1, paths.count(), "同一個 publicId 不應留下多個檔案");
        }
    }

    @Test
    @DisplayName("可疑副檔名一律落地為 .png，不以原始檔名決定型別")
    void unsafeExtensionIsNormalised() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.html", "text/html", "<script>alert(1)</script>".getBytes());

        String url = imageStorageService.upload(file, "stores", null);

        assertTrue(url.endsWith(".png"), "非圖片副檔名應被正規化為 .png，實際：" + url);
    }
}
