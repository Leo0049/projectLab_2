package com.example.demo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sms.mode 的預設值（純檔案檢查，不載入 Spring context）。
 *
 * <p>守住 N15：未設定 SMS_MODE 時必須走 firebase（fail-closed）。mock 模式會把 MOCK_TOKEN 當成
 * 「手機已驗證」，若它成為預設值，任何漏設環境變數的部署都會被接管。
 * 本機與 CI 要用 mock 時必須明確設定，不靠預設值。
 *
 * <p>測試從專案根目錄讀取設定檔（Maven 執行測試時的工作目錄即為專案根目錄）。
 */
class SmsModeDefaultTest {

    @Test
    @DisplayName("application.yml 的 sms.mode 預設為 firebase，不可回退成 mock（N15）")
    void baseConfigDefaultsToFirebase() throws IOException {
        String yml = read("src/main/resources/application.yml");
        assertTrue(yml.contains("mode: ${SMS_MODE:firebase}"), "sms.mode 的預設值必須是 firebase");
        assertFalse(yml.contains("${SMS_MODE:mock}"), "不可把 mock 設為未設定 SMS_MODE 時的預設值");
    }

    @Test
    @DisplayName("正式環境範本明確指定 firebase")
    void prodTemplatePinsFirebase() throws IOException {
        String yml = read("src/main/resources/application-prod.yml.example");
        assertTrue(yml.contains("mode: firebase"), "正式環境範本必須明確指定 firebase");
    }

    @Test
    @DisplayName("本機範本明確指定 mock，本機要用 mock 必須自己開啟")
    void localTemplateOptsIntoMockExplicitly() throws IOException {
        String yml = read("src/main/resources/application-local.yml.example");
        assertTrue(yml.contains("mode: mock"), "本機範本應明確指定 mock");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
