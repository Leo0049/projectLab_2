package com.example.demo.controller;

import com.example.demo.service.ImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通用圖片上傳的內容檢查（獨立 MockMvc，不載入 Spring context）。
 *
 * <p>守住 M-1：副檔名可以偽造，內容開頭的 magic bytes 才是判斷依據。
 * 否則把 HTML 改名成 .png 就能落到 /uploads/**，形成儲存型 XSS。
 */
class UploadControllerTest {

    /** PNG 檔頭（8 bytes）加上補齊的內容，總長超過控制器要求的 12 bytes */
    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    };

    private ImageStorageService storage;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        storage = mock(ImageStorageService.class);
        when(storage.upload(any(byte[].class), anyString(), isNull(), anyString()))
                .thenReturn("/uploads/files/stored.png");
        mvc = MockMvcBuilders.standaloneSetup(new UploadController(storage)).build();
    }

    @Test
    @DisplayName("內容確實是 PNG 的檔案上傳成功，並交給儲存服務")
    void genuinePngIsStored() throws Exception {
        mvc.perform(upload("logo.png", PNG)).andExpect(status().isOk());

        verify(storage).upload(any(byte[].class), eq("files"), isNull(), eq("logo.png"));
    }

    @Test
    @DisplayName("副檔名改成 .png 的 HTML 被拒絕（儲存型 XSS 的路徑）")
    void htmlRenamedToPngIsRejected() throws Exception {
        byte[] html = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

        mvc.perform(upload("evil.png", html)).andExpect(status().isBadRequest());

        verify(storage, never()).upload(any(byte[].class), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("副檔名不在白名單（.html）即使內容是 PNG 也被拒絕")
    void disallowedExtensionIsRejectedEvenWithPngBytes() throws Exception {
        mvc.perform(upload("page.html", PNG)).andExpect(status().isBadRequest());

        verify(storage, never()).upload(any(byte[].class), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("超過 5MB 的檔案被拒絕")
    void oversizedFileIsRejected() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);

        mvc.perform(upload("big.png", big)).andExpect(status().isBadRequest());

        verify(storage, never()).upload(any(byte[].class), anyString(), any(), anyString());
    }

    private static MockMultipartHttpServletRequestBuilder upload(String filename, byte[] content) {
        return multipart("/api/upload")
                .file(new MockMultipartFile("file", filename, "image/png", content));
    }
}
