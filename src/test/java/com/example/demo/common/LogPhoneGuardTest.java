package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 記錄檔不得含原始手機號碼（N17；純檔案檢查，不載入 Spring context）。
 *
 * <p>掃描 {@code src/main/java} 內每一個 {@code log.xxx(...)} 呼叫。呼叫的引數只要提到 phone，
 * 就必須經過 {@link PhoneMask#mask}；之後新增的記錄若忘了遮罩，這支測試會紅。
 *
 * <p>這是文字掃描、不是語法分析：每個呼叫的範圍取到第一個分號為止。
 * 測試工作目錄必須是專案根目錄（Maven 預設即是）。
 */
class LogPhoneGuardTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\(");
    private static final Pattern PHONE_WORD = Pattern.compile("(?i)phone");

    @Test
    @DisplayName("所有 log 呼叫只要提到手機，就必須經過 PhoneMask.mask()（N17）")
    void everyPhoneInALogCallIsMasked() throws IOException {
        List<String> offenders = new ArrayList<>();
        int logCalls = 0;
        for (Path file : javaSources()) {
            String src = Files.readString(file, StandardCharsets.UTF_8);
            Matcher call = LOG_CALL.matcher(src);
            while (call.find()) {
                logCalls++;
                String statement = statementFrom(src, call.start());
                if (PHONE_WORD.matcher(statement).find() && !statement.contains("PhoneMask.mask(")) {
                    offenders.add(file.getFileName() + ":" + lineOf(src, call.start()));
                }
            }
        }
        // 防止正規式寫壞時整支測試假性通過
        assertTrue(logCalls > 0, "沒有找到任何 log 呼叫，掃描可能沒有作用");
        assertTrue(offenders.isEmpty(), "以下 log 呼叫含手機欄位卻未遮罩：" + offenders);
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String statementFrom(String src, int start) {
        int end = src.indexOf(';', start);
        return end < 0 ? src.substring(start) : src.substring(start, end + 1);
    }

    private static int lineOf(String src, int index) {
        return (int) src.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }
}
