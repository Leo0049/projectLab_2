package com.example.demo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排程位置守衛（stage 25，不載入 Spring context）。
 *
 * <p>到期券原本有兩支排程：{@code ScheduledTasks} 的 00:00 與 {@code CouponExpiryScheduler} 的 00:01，
 * 做同一件事卻各寫一份。這裡以文字掃描守住三件事，每一件對應一種可能的回歸：
 * <ol>
 *   <li>{@code @Scheduled} 的清單固定，且只能出現在 {@code ScheduledTasks}。
 *       不論新排程放在哪個檔案，清單對不上就紅。</li>
 *   <li>每一支 {@code @Scheduled} 的方法本體都必須經 {@code exclusiveJobRunner.runExclusively(}，
 *       不可直接呼叫服務而繞過互斥（多實例時會各跑一次）。</li>
 *   <li>到期券的查詢與轉換各只有一個呼叫端。重新加回第二條路徑就紅。</li>
 * </ol>
 * 新增排程、或新增到期券的呼叫端時，同一個 PR 要更新下方的清單並寫明理由。
 */
class SchedulingLocationGuardTest {

    /** 掃描根目錄（與 CustomerRevocationCutGuardTest 相同的相對路徑慣例） */
    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /** 允許的排程（檔名#方法）；新增排程時請同步更新 */
    private static final Set<String> EXPECTED_SCHEDULED = Set.of(
            "ScheduledTasks#expireCouponsDaily",
            "ScheduledTasks#autoRejectExpiredPendingOrders");

    /** 到期券查詢（findByStatusAndExpiredAtBefore）允許的呼叫端 */
    private static final Set<String> EXPECTED_EXPIRY_QUERY_CALLERS = Set.of("CouponService#expireOldCoupons");

    /** 到期券轉換（expireOldCoupons）允許的呼叫端 */
    private static final Set<String> EXPECTED_EXPIRY_JOB_CALLERS = Set.of("ScheduledTasks#expireCouponsDaily");

    /** 原始文字中的 @Scheduled 行，用來確認掃描沒有漏掉任何標註 */
    private static final Pattern SCHEDULED_LINE = Pattern.compile("^[ \\t]*@Scheduled\\b", Pattern.MULTILINE);
    /** 成員區塊開頭的 @Scheduled 註解 */
    private static final Pattern SCHEDULED_ANNOTATION = Pattern.compile("^    @Scheduled\\b");
    /** 成員區塊開頭的任何註解 */
    private static final Pattern ANY_ANNOTATION = Pattern.compile("^    @");
    /** 區塊首行中，第一個左括號之前的識別字就是成員名稱 */
    private static final Pattern NAME_BEFORE_PAREN = Pattern.compile("(\\w+)\\s*$");
    private static final Pattern RUNNER_CALL = Pattern.compile("exclusiveJobRunner\\.runExclusively\\(");
    private static final Pattern EXPIRY_QUERY_CALL = Pattern.compile("\\.findByStatusAndExpiredAtBefore\\(");
    private static final Pattern EXPIRY_JOB_CALL = Pattern.compile("(?:\\.|::)expireOldCoupons\\b");

    /** 一個類別成員的區塊：「檔名#名稱」、區塊文字，以及是否緊接在 @Scheduled 之後 */
    private record Member(String site, String body, boolean scheduled) {
    }

    /** 掃描結果：所有成員、掃到的檔案數、原始文字中的 @Scheduled 行數 */
    private record Scan(List<Member> members, int files, long scheduledLines) {
    }

    @Test
    @DisplayName("@Scheduled 的清單固定，且只能出現在 ScheduledTasks；掃描必須看見原始文字中的每一行 @Scheduled")
    void scheduledJobsAreTheKnownListAndLiveInScheduledTasks() throws IOException {
        Scan scan = scan();

        assertTrue(scan.files() >= 50, "掃描的檔案只有 " + scan.files() + " 個，守衛可能失效");
        Set<String> actual = scan.members().stream()
                .filter(Member::scheduled)
                .map(Member::site)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(EXPECTED_SCHEDULED), actual, "排程清單與預期不符（新增排程請同步更新本測試）");
        assertEquals(scan.scheduledLines(), actual.size(),
                "原始文字的 @Scheduled 行數與掃到的排程數不一致，掃描可能漏掉了某些方法");
    }

    @Test
    @DisplayName("每一支 @Scheduled 的本體都必須經 ExclusiveJobRunner 互斥執行，不可直接呼叫服務")
    void everyScheduledJobGoesThroughTheExclusiveRunner() throws IOException {
        List<String> bypassing = scan().members().stream()
                .filter(Member::scheduled)
                .filter(m -> !RUNNER_CALL.matcher(code(m.body())).find())
                .map(Member::site)
                .sorted()
                .toList();

        assertTrue(bypassing.isEmpty(), "排程沒有經 ExclusiveJobRunner 包住：" + bypassing);
    }

    @Test
    @DisplayName("到期券的查詢與轉換各只有一個呼叫端：重新加回第二條路徑（不論新排程或新呼叫）就會紅")
    void couponExpiryIsReachedFromExactlyOneEntryPoint() throws IOException {
        Scan scan = scan();

        assertEquals(EXPECTED_EXPIRY_QUERY_CALLERS, callersOf(scan, EXPIRY_QUERY_CALL),
                "findByStatusAndExpiredAtBefore 的呼叫端");
        assertEquals(EXPECTED_EXPIRY_JOB_CALLERS, callersOf(scan, EXPIRY_JOB_CALL),
                "expireOldCoupons 的呼叫端");
    }

    private static Set<String> callersOf(Scan scan, Pattern call) {
        return scan.members().stream()
                .filter(m -> call.matcher(code(m.body())).find())
                .map(Member::site)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Scan scan() throws IOException {
        List<Member> members = new ArrayList<>();
        int files = 0;
        long scheduledLines = 0;
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                files++;
                String source = Files.readString(file, StandardCharsets.UTF_8);
                scheduledLines += SCHEDULED_LINE.matcher(source).results().count();
                boolean pendingScheduled = false;
                for (String chunk : memberChunks(source)) {
                    String text = code(chunk);
                    if (text.isBlank()) {
                        // 註解或空白區塊，不改變 @Scheduled 的歸屬
                        continue;
                    }
                    if (ANY_ANNOTATION.matcher(text).find()) {
                        pendingScheduled |= SCHEDULED_ANNOTATION.matcher(text).find();
                        continue;
                    }
                    String name = memberName(text);
                    members.add(new Member(stem(file) + "#" + (name == null ? "?" : name), chunk, pendingScheduled));
                    pendingScheduled = false;
                }
            }
        }
        return new Scan(members, files, scheduledLines);
    }

    /** 去掉區塊註解與行註解，避免註解裡提到的呼叫被誤認為程式碼。 */
    private static String code(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String memberName(String text) {
        int newline = text.indexOf('\n');
        String head = newline < 0 ? text : text.substring(0, newline);
        int paren = head.indexOf('(');
        if (paren < 0) {
            return null;
        }
        Matcher name = NAME_BEFORE_PAREN.matcher(head.substring(0, paren));
        return name.find() ? name.group(1) : null;
    }

    /**
     * 以「四個空白後接非空白字元」的行切成成員區塊，與 CustomerRevocationCutGuardTest 相同的縮排慣例。
     * 因此 @Scheduled 與它所標註的方法會分屬相鄰的兩個區塊。
     */
    private static List<String> memberChunks(String source) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            boolean boundary = line.length() > 4 && line.startsWith("    ") && !Character.isWhitespace(line.charAt(4));
            if (boundary && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }
}
