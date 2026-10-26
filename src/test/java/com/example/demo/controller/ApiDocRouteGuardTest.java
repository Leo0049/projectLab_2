package com.example.demo.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API.md 與 controller 路由一致（stage 20；純文字掃描，不載入 Spring context）。
 *
 * <p>controller 的路由取自 {@code @RequestMapping}、{@code @GetMapping} 等標記，含類別層級的前綴；
 * API.md 的路由取自表格列（{@code | METHOD | path | ... |}）。路徑參數一律正規化為 {@code {}}，
 * 查詢字串不納入比對。正文中提到的 /api 路徑不納入檢查，因為文件可能刻意描述已移除的端點。
 *
 * <ul>
 *   <li>UNDOC：controller 有，API.md 表格沒有（文件不完整）</li>
 *   <li>PHANTOM：API.md 表格列出，controller 沒有該路徑（文件漂移）</li>
 *   <li>METHOD：路徑存在，但 API.md 寫的 method 與程式不同</li>
 * </ul>
 *
 * <p>唯一的例外是 {@code GET /actuator/health}：它由 Actuator 提供，不在 controller 內。
 * 另一支測試確認每個 controller 檔都解析得到 public class，否則掃描會靜默略過它。
 * 測試工作目錄必須是專案根目錄（Maven 預設即是）。
 */
class ApiDocRouteGuardTest {

    private static final Path CONTROLLER_DIR = Path.of("src/main/java/com/example/demo/controller");
    private static final Path API_DOC = Path.of("API.md");
    private static final String ACTUATOR_HEALTH = "GET /actuator/health";
    private static final int MIN_ROUTES = 100;

    private static final Pattern ANNOTATION = Pattern.compile(
            "@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\\b"
                    + "(?:\\s*\\(((?:[^()]|\\([^()]*\\))*)\\))?");
    private static final Pattern CLASS_DECL = Pattern.compile(
            "^\\s*public\\s+(?:final\\s+|abstract\\s+)?class\\s+\\w+", Pattern.MULTILINE);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PATH_ARG = Pattern.compile(
            "\\b(?:value|path)\\s*=\\s*(\\{[^}]*\\}|\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern NAMED_ARG_AFTER = Pattern.compile(",\\s*\\w+\\s*=");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE)");
    private static final Pattern TABLE_ROW = Pattern.compile(
            "^\\|\\s*(GET|POST|PUT|PATCH|DELETE)\\s*\\|\\s*`?(/[^`|\\s]*)`?");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{[^}:]+(?::[^}]*)?\\}");

    private record Route(String method, String path, String source, int line) {
    }

    @Test
    @DisplayName("controller 的每一條路由都必須列在 API.md 的表格中（UNDOC）")
    void everyControllerRouteIsDocumented() throws IOException {
        List<Route> documented = documentedRoutes();
        List<String> undocumented = new ArrayList<>();
        for (Route route : distinctRoutes(controllerRoutes())) {
            if (!isDocumented(documented, route.method(), route.path())) {
                undocumented.add(route.method() + " " + route.path()
                        + "（" + route.source() + ":" + route.line() + "）");
            }
        }
        assertTrue(undocumented.isEmpty(), "以下 controller 路由沒有列在 API.md：" + undocumented);
    }

    @Test
    @DisplayName("API.md 表格列出的每一條路由都必須存在於 controller（PHANTOM／METHOD，唯一例外是 GET /actuator/health）")
    void everyDocumentedRouteExistsInAController() throws IOException {
        Map<String, Set<String>> methodsByPath = methodsByPath(controllerRoutes());
        List<String> problems = new ArrayList<>();
        for (Route doc : documentedRoutes()) {
            String key = doc.method() + " " + doc.path();
            if (key.equals(ACTUATOR_HEALTH)) {
                continue;
            }
            Set<String> implemented = methodsByPath.get(doc.path());
            if (implemented == null) {
                problems.add("PHANTOM API.md:" + doc.line() + " " + key);
            } else if (!implemented.contains("ANY") && !implemented.contains(doc.method())) {
                problems.add("METHOD API.md:" + doc.line() + " " + key
                        + "（程式為 " + String.join("、", implemented) + "）");
            }
        }
        assertTrue(problems.isEmpty(), "API.md 與 controller 不一致：" + problems);
    }

    @Test
    @DisplayName("掃描必須真的有作用：每個 controller 檔都要解析得到 public class，且找到足夠多的路由（防止假性通過）")
    void scanIsMeaningful() throws IOException {
        List<String> withoutClass = new ArrayList<>();
        for (Path file : controllerFiles()) {
            String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!CLASS_DECL.matcher(text).find()) {
                withoutClass.add(file.getFileName().toString());
            }
        }
        assertTrue(withoutClass.isEmpty(), "以下 controller 檔找不到 public class，掃描會略過它們：" + withoutClass);
        int routes = distinctRoutes(controllerRoutes()).size();
        int rows = documentedRoutes().size();
        assertTrue(routes >= MIN_ROUTES, "controller 路由只找到 " + routes + " 筆，少於 " + MIN_ROUTES + "；掃描可能已失效");
        assertTrue(rows >= MIN_ROUTES, "API.md 表格只找到 " + rows + " 列，少於 " + MIN_ROUTES + "；解析可能已失效");
    }

    // ---- controller 端 ----

    private static List<Route> controllerRoutes() throws IOException {
        List<Route> routes = new ArrayList<>();
        for (Path file : controllerFiles()) {
            routes.addAll(scanController(file));
        }
        return routes;
    }

    private static List<Path> controllerFiles() throws IOException {
        try (Stream<Path> files = Files.list(CONTROLLER_DIR)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static List<Route> scanController(Path file) throws IOException {
        String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
        Matcher classDecl = CLASS_DECL.matcher(text);
        if (!classDecl.find()) {
            return List.of();
        }
        String head = text.substring(0, classDecl.start());
        String body = text.substring(classDecl.start());
        List<String> prefixes = List.of("");
        Matcher classAnnotation = ANNOTATION.matcher(head);
        while (classAnnotation.find()) {
            if (classAnnotation.group(1).equals("RequestMapping")) {
                prefixes = pathsFromArgs(classAnnotation.group(2));
            }
        }
        String source = file.getFileName().toString();
        List<Route> routes = new ArrayList<>();
        Matcher annotation = ANNOTATION.matcher(body);
        while (annotation.find()) {
            String name = annotation.group(1);
            String args = annotation.group(2);
            int line = lineOf(text, classDecl.start() + annotation.start());
            for (String subPath : pathsFromArgs(args)) {
                for (String prefix : prefixes) {
                    String full = join(prefix, subPath);
                    for (String method : methodsFor(name, args)) {
                        routes.add(new Route(method, full, source, line));
                    }
                }
            }
        }
        return routes;
    }

    // ---- API.md 端 ----

    private static List<Route> documentedRoutes() throws IOException {
        List<Route> rows = new ArrayList<>();
        String[] lines = Files.readString(API_DOC, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher row = TABLE_ROW.matcher(lines[i]);
            if (row.lookingAt()) {
                rows.add(new Route(row.group(1), norm(row.group(2)), "API.md", i + 1));
            }
        }
        return rows;
    }

    // ---- 比對與解析（與 stage 14 的比對腳本一致） ----

    private static boolean isDocumented(List<Route> documented, String method, String path) {
        return documented.stream().anyMatch(doc -> doc.path().equals(path)
                && (method.equals("ANY") || doc.method().equals(method)));
    }

    private static Map<String, Set<String>> methodsByPath(List<Route> routes) {
        Map<String, Set<String>> byPath = new HashMap<>();
        for (Route route : routes) {
            byPath.computeIfAbsent(route.path(), k -> new TreeSet<>()).add(route.method());
        }
        return byPath;
    }

    private static List<Route> distinctRoutes(List<Route> routes) {
        Map<String, Route> firstSeen = new LinkedHashMap<>();
        for (Route route : routes) {
            firstSeen.putIfAbsent(route.method() + " " + route.path(), route);
        }
        return new ArrayList<>(firstSeen.values());
    }

    private static List<String> pathsFromArgs(String args) {
        if (args == null || args.isEmpty()) {
            return List.of("");
        }
        String part = args;
        Matcher path = PATH_ARG.matcher(args);
        if (path.find()) {
            part = path.group(1);
        } else {
            Matcher cut = NAMED_ARG_AFTER.matcher(args);
            if (cut.find()) {
                part = args.substring(0, cut.start());
            }
        }
        List<String> found = new ArrayList<>();
        Matcher literal = STRING_LITERAL.matcher(part);
        while (literal.find()) {
            found.add(literal.group(1));
        }
        return found.isEmpty() ? List.of("") : found;
    }

    private static List<String> methodsFor(String annotation, String args) {
        if (annotation.equals("RequestMapping")) {
            List<String> methods = new ArrayList<>();
            Matcher method = REQUEST_METHOD.matcher(args == null ? "" : args);
            while (method.find()) {
                methods.add(method.group(1));
            }
            return methods.isEmpty() ? List.of("ANY") : methods;
        }
        return List.of(annotation.substring(0, annotation.length() - "Mapping".length())
                .toUpperCase(Locale.ROOT));
    }

    private static String join(String prefix, String path) {
        String suffix = path;
        if (!suffix.isEmpty() && !suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return norm(prefix + suffix);
    }

    /** 去掉反引號與查詢字串，{x:regex} 與 {x} 都變成 {}，合併重複斜線，去掉結尾斜線。 */
    private static String norm(String raw) {
        String p = raw.strip().replaceAll("^`+|`+$", "").strip();
        int query = p.indexOf('?');
        if (query >= 0) {
            p = p.substring(0, query);
        }
        p = PATH_PARAM.matcher(p).replaceAll("{}");
        p = p.replaceAll("/+", "/");
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? "/" : p;
    }

    /**
     * 移除行註解與區塊註解；字串與字元常值內的斜線不算註解。保留換行，行號仍對得上原檔。
     * 不用正規式直接刪區塊註解：行註解裡的路徑（例如含有兩個星號的萬用字元）會被誤當成區塊註解的開頭。
     */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (text.startsWith("\"\"\"", i)) {
                int j = text.indexOf("\"\"\"", i + 3);
                j = j == -1 ? n : j + 3;
                out.append(text, i, j);
                i = j;
                continue;
            }
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && text.charAt(j) != c && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                out.append(text, i, j);
                i = j;
                continue;
            }
            if (text.startsWith("//", i)) {
                int j = text.indexOf('\n', i);
                i = j == -1 ? n : j;
                continue;
            }
            if (text.startsWith("/*", i)) {
                int j = text.indexOf("*/", i + 2);
                int end = j == -1 ? n : j + 2;
                for (int k = i; k < end; k++) {
                    if (text.charAt(k) == '\n') {
                        out.append('\n');
                    }
                }
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
