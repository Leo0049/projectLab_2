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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端的 inline 事件屬性不得插入 {@code ${…}}（N-14；純檔案檢查，不載入 Spring context）。
 *
 * <p>事件屬性（{@code onclick}、{@code onerror} 等）的值是 JavaScript 字串，HTML 轉義擋不住字串跳脫，
 * 所以要改用 {@code data-*} 屬性，由事件函式讀取 {@code this.dataset}（見 CLAUDE.md「2026-10-23」一節）。
 *
 * <p>掃描 {@code frontend/} 下所有 {@code .html} 與 {@code .js}（排除 {@code vendor/}）。某個 {@code on…=}
 * 屬性的值在同一個引號之內出現 {@code ${}，即為違規。這是文字掃描、不是語法分析；
 * 行為層面由 {@code scripts/ui/flow-inline-controls.js} 守住。
 *
 * <p>測試工作目錄必須是專案根目錄（Maven 預設即是）。
 */
class InlineHandlerInterpolationGuardTest {

    private static final Path FRONTEND_ROOT = Path.of("frontend");

    /**
     * {@code on…=} 之後，同一個屬性值內出現 {@code ${} 即違規。屬性值有四種引號寫法：原始的 {@code "} 與 {@code '}，
     * 以及在 JS 字串中跳脫的 {@code \"} 與 {@code \'}。值只會被「同種」引號結束，所以值內的跳脫引號
     * （例如 {@code onclick="f(\"${x}\")"}）不會提早結束掃描；而屬性之外的插值（例如標籤內容）不會被誤抓。
     */
    private static final Pattern INLINE_HANDLER_WITH_INTERPOLATION = Pattern.compile(
            "(?i)(?<![\\w-])on[a-z]+\\s*=\\s*(?:"
                    + "\\\\\"(?:[^\\\\\"]|\\\\(?!\"))*?\\$\\{"
                    + "|\"(?:[^\"\\\\]|\\\\[\\s\\S])*?\\$\\{"
                    + "|\\\\'(?:[^\\\\']|\\\\(?!'))*?\\$\\{"
                    + "|'(?:[^'\\\\]|\\\\[\\s\\S])*?\\$\\{"
                    + "|\\$\\{)");

    /** 任何 {@code on…=} 屬性，不論是否插值；用來確認掃描確實看見了事件屬性。 */
    private static final Pattern ANY_INLINE_HANDLER = Pattern.compile(
            "(?i)(?<![\\w-])on[a-z]+\\s*=\\s*\\\\?[\"']");

    @Test
    @DisplayName("前端 inline 事件屬性不得插入 ${…}（N-14）")
    void noInlineHandlerInterpolatesAValue() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path file : frontendSources()) {
            scanned++;
            String src = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = INLINE_HANDLER_WITH_INTERPOLATION.matcher(src);
            while (m.find()) {
                offenders.add(file + ":" + lineOf(src, m.start()));
            }
        }
        // 防止路徑或正規式寫壞時整支測試假性通過
        assertTrue(scanned > 0, "沒有找到任何前端檔案，掃描可能沒有作用");
        assertTrue(offenders.isEmpty(),
                "以下 inline 事件屬性插入了 ${…}，請改用 data-* 與 this.dataset：" + offenders);
    }

    @Test
    @DisplayName("掃描器確實看得見事件屬性（防止整支測試假性通過）")
    void scannerSeesInlineHandlers() throws IOException {
        int handlers = 0;
        for (Path file : frontendSources()) {
            Matcher m = ANY_INLINE_HANDLER.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                handlers++;
            }
        }
        assertTrue(handlers > 0, "沒有找到任何 on…= 事件屬性，掃描可能沒有作用");
    }

    @Test
    @DisplayName("正規式：插值的事件屬性會被抓到；data-* 與屬性之外的插值不會被誤抓")
    void patternCatchesInterpolationAndAllowsDataAttributes() {
        String bad = "<button onclick=\"remove('${item.name}')\">";
        String badEscapedInJs = "html += `<button onclick=\\\"remove('${name}')\\\">`;";
        String badEscapedQuoteInside = "<button onclick=\"remove(\\\"${name}\\\")\">";
        String good = "<button data-name=\"${name}\" onclick=\"remove(this.dataset.name)\">";
        String outsideAttribute = "html += '<button onclick=\\\"go()\\\">${label}</button>';";
        assertTrue(INLINE_HANDLER_WITH_INTERPOLATION.matcher(bad).find(),
                "應抓到 onclick=\"…${…}\"");
        assertTrue(INLINE_HANDLER_WITH_INTERPOLATION.matcher(badEscapedInJs).find(),
                "應抓到 JS 字串中跳脫引號的 onclick");
        assertTrue(INLINE_HANDLER_WITH_INTERPOLATION.matcher(badEscapedQuoteInside).find(),
                "值內的跳脫引號不應提早結束掃描");
        assertFalse(INLINE_HANDLER_WITH_INTERPOLATION.matcher(good).find(),
                "data-* 的插值不是事件屬性，不應被抓到");
        assertFalse(INLINE_HANDLER_WITH_INTERPOLATION.matcher(outsideAttribute).find(),
                "屬性值之外的插值（標籤內容）不是事件屬性插值，不應被抓到");
    }

    private static List<Path> frontendSources() throws IOException {
        try (Stream<Path> files = Files.walk(FRONTEND_ROOT)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().replace('\\', '/').contains("/vendor/"))
                    .filter(p -> p.toString().endsWith(".html") || p.toString().endsWith(".js"))
                    .sorted()
                    .toList();
        }
    }

    private static int lineOf(String src, int index) {
        return (int) src.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }
}
