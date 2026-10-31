package com.example.demo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顧客權杖撤銷的切斷守衛（stage 21，不載入 Spring context）。
 *
 * <ol>
 *   <li>文字掃描：{@code src/main/java} 中凡是呼叫 {@code changePasswordHash} 或 {@code revokeIssuedTokens}
 *       的方法，都必須在同一個方法內發布 {@code AccountsRevokedEvent.forCustomer}。
 *       日後新增改密碼或撤銷路徑而忘了發布，這裡會紅。</li>
 *   <li>反射：上述六個方法都必須標註 {@code @Transactional}。撤銷事件由 {@code AFTER_COMMIT} 監聽器處理，
 *       在交易外發布的事件會被丟棄，切斷就不會發生。</li>
 * </ol>
 */
class CustomerRevocationCutGuardTest {

    /** 掃描根目錄（與 LogPhoneGuardTest 相同的相對路徑慣例） */
    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /** 呼叫端一律有前導的點號；entity/User.java 內的裸呼叫（定義處）不符合此形式 */
    private static final Pattern REVOCATION_CALL =
            Pattern.compile("\\.(changePasswordHash|revokeIssuedTokens)\\(");

    /** 方法內必須出現的發布語句 */
    private static final String PUBLISH = "AccountsRevokedEvent.forCustomer(";

    /** 已知的撤銷呼叫端（檔名#方法）；掃描必須全部找得到，否則守衛本身已經失效 */
    private static final Set<String> KNOWN_SITES = Set.of(
            "UserService#changePassword",
            "UserService#logoutAllDevices",
            "UserProfileService#deleteMe",
            "AuthService#mergeSetPassword",
            "AuthService#mergeSetPasswordAndBindSocial",
            "AuthService#resetPasswordWithFirebase");

    /** 成員區塊的第一行：類別成員在第 4 欄，修飾詞之後是回傳型別與方法名稱 */
    private static final Pattern MEMBER_NAME =
            Pattern.compile("^    (?:public|private|protected)\\s.*?\\b(\\w+)\\s*\\(");

    /** 掃描結果：「檔名#方法 → 是否發布」，以及掃描到的檔案數 */
    private record Scan(Map<String, Boolean> sites, int files) {
    }

    @Test
    @DisplayName("撤銷顧客權杖的每個方法都必須在同一方法內發布 AccountsRevokedEvent.forCustomer")
    void everyCustomerRevocationPublishesTheEvent() throws IOException {
        Scan scan = scan();

        assertTrue(scan.files() >= 50, "掃描的檔案只有 " + scan.files() + " 個，守衛可能失效");
        List<String> missing = KNOWN_SITES.stream()
                .filter(site -> !scan.sites().containsKey(site)).sorted().toList();
        assertTrue(missing.isEmpty(), "掃描漏掉已知的撤銷位置：" + missing);
        List<String> silent = scan.sites().entrySet().stream()
                .filter(e -> !e.getValue()).map(Map.Entry::getKey).sorted().toList();
        assertTrue(silent.isEmpty(), "撤銷權杖卻沒有發布 AccountsRevokedEvent.forCustomer 的方法：" + silent);
    }

    @Test
    @DisplayName("撤銷顧客權杖的方法都必須標註 @Transactional，否則 AFTER_COMMIT 的切斷事件會被丟棄")
    void revocationMethodsRunInsideATransaction() {
        assertTransactional(UserService.class, "changePassword");
        assertTransactional(UserService.class, "logoutAllDevices");
        assertTransactional(UserProfileService.class, "deleteMe");
        assertTransactional(AuthService.class, "mergeSetPassword");
        assertTransactional(AuthService.class, "mergeSetPasswordAndBindSocial");
        assertTransactional(AuthService.class, "resetPasswordWithFirebase");
    }

    private static void assertTransactional(Class<?> type, String methodName) {
        List<Method> methods = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName)).toList();
        assertEquals(1, methods.size(), type.getSimpleName() + "." + methodName + " 應只有一個多載");
        boolean transactional = isTransactional(methods.get(0)) || isTransactional(type);
        assertTrue(transactional, type.getSimpleName() + "." + methodName + " 必須標註 @Transactional");
    }

    private static boolean isTransactional(AnnotatedElement element) {
        return element.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)
                || element.isAnnotationPresent(jakarta.transaction.Transactional.class);
    }

    private static Scan scan() throws IOException {
        Map<String, Boolean> sites = new TreeMap<>();
        int files = 0;
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                // entity/User.java 是撤銷方法的定義處，不是呼叫端
                if (file.endsWith(Path.of("entity", "User.java"))) {
                    continue;
                }
                files++;
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String member : memberChunks(source)) {
                    if (!REVOCATION_CALL.matcher(member).find()) {
                        continue;
                    }
                    Matcher name = MEMBER_NAME.matcher(member);
                    String methodName = name.find() ? name.group(1) : "?";
                    String key = stem(file) + "#" + methodName;
                    sites.merge(key, member.contains(PUBLISH), Boolean::logicalAnd);
                }
            }
        }
        return new Scan(sites, files);
    }

    /**
     * 以「四個空白後接非空白字元」的行切成成員區塊。本專案的縮排慣例：類別成員在第 4 欄，
     * 方法本體在第 8 欄以後，因此一個方法的簽章與本體會落在同一個區塊。
     */
    static List<String> memberChunks(String source) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            boolean boundary = line.length() > 4 && line.startsWith("    ")
                    && !Character.isWhitespace(line.charAt(4));
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
