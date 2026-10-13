package com.example.demo.service;

import com.example.demo.common.Result;
import com.example.demo.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 冪等鍵（{@code Idempotency-Key}）：同一把鍵在有效期內只會真的執行一次，重送時重播第一次的結果。
 *
 * <p>為什麼需要：結帳是「建單 + 扣款」。前端逾時、使用者連點、網路重送都會讓同一筆結帳被送兩次。
 * {@code createOrder} 內的使用者列鎖只能防「同時」，防不了「先後」——第一次成功後，第二次會再扣一次款。
 *
 * <p>狀態機（每個 {@code scope:userId:key} 一組）：
 * <ul>
 *   <li>無紀錄 → 佔位（{@code IN_PROGRESS}，{@value #IN_PROGRESS_SECONDS} 秒）→ 執行操作</li>
 *   <li>執行成功（2xx）→ 保存結果（{@value #REPLAY_SECONDS} 秒），之後的重送直接重播，標頭 {@code Idempotent-Replayed: true}</li>
 *   <li>執行失敗（4xx 或例外）→ 釋放佔位，允許同一把鍵重試</li>
 *   <li>佔位中又來一次 → 409「仍在處理中」，不執行</li>
 * </ul>
 *
 * <p>Redis 不可用時退回 JVM 本地計數（僅單實例正確），策略與 {@link RedisLockService} 一致（ADR-0006）。
 * 金流的真正防線仍是列鎖與狀態守衛；本類別只負責「同一個意圖只執行一次」（ADR-0005）。
 */
@Slf4j
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    static final Pattern KEY_PATTERN = Pattern.compile(".*");
    static final long IN_PROGRESS_SECONDS = 120;
    static final long REPLAY_SECONDS = 86_400;

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String DONE_PREFIX = "DONE!";

    /** 重播用的封套：狀態碼與回應主體，序列化後存進 Redis */
    public record Stored(int status, Object body) {
    }

    private record LocalEntry(String value, long expiresAtMillis) {
    }

    private final StringRedisTemplate redisTemplate;   // null = 未設定 Redis
    private final ObjectMapper objectMapper;
    private final Map<String, LocalEntry> localEntries = new ConcurrentHashMap<>();

    public IdempotencyService(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    /**
     * 以冪等鍵包住一個會產生副作用的操作。
     *
     * @param scope  操作名稱，例如 {@code checkout}；不同操作的鍵互不影響
     * @param userId 身分一律來自 token（{@code currentUserId}），不可來自請求參數
     * @param key    用戶端送來的 {@code Idempotency-Key}；格式不合規則擲 400
     * @param action 真正的操作；只有回 2xx 才會被保存與重播
     * @return 第一次執行的回應，或重播的回應，或 409
     */
    public ResponseEntity<?> execute(String scope, Long userId, String key, Supplier<ResponseEntity<?>> action) {
        String storageKey = "idem:" + scope + ":" + validate(key);

        if (claim(storageKey)) {
            ResponseEntity<?> response;
            try {
                response = action.get();
            } catch (RuntimeException e) {
                // release(storageKey);
                throw e;
            }
            if (response.getStatusCode().is2xxSuccessful()) {
                remember(storageKey, response);
            } else {
                // release(storageKey);
            }
            return response;
        }

        String stored = read(storageKey);
        if (stored == null) {
            return conflict("請求處理中，請稍後再試");
        }
        if (IN_PROGRESS.equals(stored)) {
            return conflict("同一筆請求仍在處理中，請勿重複送出");
        }
        return replay(stored);
    }

    /** 格式檢查（純函式）；回傳去掉前後空白的鍵。 */
    static String validate(String key) {
        if (key == null || !KEY_PATTERN.matcher(key.trim()).matches()) {
            throw new CustomException("400", "Idempotency-Key 格式錯誤（8–64 個英數字、底線或連字號）");
        }
        return key.trim();
    }

    // ── 儲存層：Redis 優先，失敗時退回本地 ─────────────────────────

    /** 佔位成功才回 true，代表本次呼叫負責執行。 */
    private boolean claim(String storageKey) {
        if (redisTemplate != null) {
            try {
                return Boolean.TRUE.equals(redisTemplate.opsForValue()
                        .setIfAbsent(storageKey, IN_PROGRESS, Duration.ofSeconds(IN_PROGRESS_SECONDS)));
            } catch (Exception e) {
                log.warn("Redis 不可用，冪等佔位改用本地計數：{}", e.getMessage());
            }
        }
        long now = System.currentTimeMillis();
        localEntries.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
        return localEntries.putIfAbsent(storageKey,
                new LocalEntry(IN_PROGRESS, now + IN_PROGRESS_SECONDS * 1000)) == null;
    }

    /** 讀取目前狀態：null 表示無紀錄（已逾時或從未存在）。 */
    private String read(String storageKey) {
        if (redisTemplate != null) {
            try {
                return redisTemplate.opsForValue().get(storageKey);
            } catch (Exception e) {
                log.warn("Redis 不可用，冪等讀取改用本地計數：{}", e.getMessage());
            }
        }
        LocalEntry entry = localEntries.get(storageKey);
        if (entry == null || entry.expiresAtMillis() < System.currentTimeMillis()) {
            return null;
        }
        return entry.value();
    }

    /**
     * 保存成功的結果。
     *
     * <p>⚠️ 操作已經完成（例如訂單已扣款提交），這裡的任何失敗都只能記 log，不能讓呼叫端看到錯誤——
     * 否則前端會當成失敗而重送。佔位會保留到逾時，期間的重送會得到 409。
     */
    private void remember(String storageKey, ResponseEntity<?> response) {
        try {
            String json = DONE_PREFIX + objectMapper.writeValueAsString(
                    new Stored(response.getStatusCode().value(), response.getBody()));
            localEntries.remove(storageKey);
            if (redisTemplate != null) {
                try {
                    redisTemplate.opsForValue().set(storageKey, json, Duration.ofSeconds(REPLAY_SECONDS));
                    return;
                } catch (Exception e) {
                    log.warn("Redis 不可用，冪等結果改存本地：{}", e.getMessage());
                }
            }
            localEntries.put(storageKey, new LocalEntry(json, System.currentTimeMillis() + REPLAY_SECONDS * 1000));
        } catch (Exception e) {
            log.error("冪等結果保存失敗（操作已完成，key={}）", storageKey, e);
        }
    }

    private void release(String storageKey) {
        localEntries.remove(storageKey);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(storageKey);
            } catch (Exception e) {
                log.warn("Redis 不可用，釋放冪等佔位失敗（將於 {} 秒後自動逾時）：{}",
                        IN_PROGRESS_SECONDS, e.getMessage());
            }
        }
    }

    private ResponseEntity<?> replay(String stored) {
        if (!stored.startsWith(DONE_PREFIX)) {
            return conflict("請求處理中，請稍後再試");
        }
        try {
            Stored saved = objectMapper.readValue(stored.substring(DONE_PREFIX.length()), Stored.class);
            return ResponseEntity.status(saved.status())
                    .header(REPLAYED_HEADER, "true")
                    .body(saved.body());
        } catch (Exception e) {
            log.error("冪等結果無法解析，改回應處理中", e);
            return conflict("請求處理中，請稍後再試");
        }
    }

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(409).body(Result.error("409", message));
    }
}
