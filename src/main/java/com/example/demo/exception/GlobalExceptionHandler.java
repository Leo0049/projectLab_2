package com.example.demo.exception;

import com.example.demo.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({DataIntegrityViolationException.class, JpaSystemException.class})
    public ResponseEntity<Result> handleDataIntegrityException(Exception e) {
        Throwable rootCause = getRootCause(e);
        String message = rootCause != null && rootCause.getMessage() != null
                ? rootCause.getMessage()
                : e.getMessage();

        if (containsIgnoreCase(message, "Duplicate brand spec display name")
                || containsIgnoreCase(message, "uk_spec_master_type_name")
                || containsIgnoreCase(message, "uk_brand_spec_setting_brand_type_custom")
                || containsIgnoreCase(message, "uk_brand_spec_setting_brand_master")) {
            return ResponseEntity.status(409).body(Result.error("409", "此規格名稱已重複"));
        }

        if (containsIgnoreCase(message, "Duplicate brand topping display name")
                || containsIgnoreCase(message, "uk_topping_master_name")
                || containsIgnoreCase(message, "uk_brand_topping_setting_brand_custom")
                || containsIgnoreCase(message, "uk_brand_topping_setting_brand_master")) {
            return ResponseEntity.status(409).body(Result.error("409", "此配料名稱已重複"));
        }

        // 原始 DB 錯誤訊息可能含資料表/欄位結構，只寫進 log，不回傳給前端
        log.error("資料庫操作失敗", e);
        return ResponseEntity.status(500).body(Result.error("500", "系統錯誤，請稍後再試"));
    }

    // ── 用戶端錯誤：以往全數落入 handleGeneralException 被回成 500 ──────────

    /** 路由不存在（含前端打錯 API path）→ 404，而非 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(Result.error("404", "找不到此資源"));
    }

    /** 缺少必要 query 參數 → 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(400).body(Result.error("400", "缺少必要參數：" + e.getParameterName()));
    }

    /** 參數型別不符（例如 id 傳了非數字）→ 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(400).body(Result.error("400", "參數格式錯誤：" + e.getName()));
    }

    /** request body 無法解析（JSON 格式錯誤）→ 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400).body(Result.error("400", "請求內容格式錯誤"));
    }

    /** HTTP method 不支援 → 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(Result.error("405", "不支援的請求方法"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleGeneralException(Exception e) {
        // 例外細節可能含內部實作資訊，只寫進 log，不回傳給前端
        log.error("未預期的系統例外", e);
        return ResponseEntity.status(500).body(Result.error("500", "系統錯誤，請稍後再試"));
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<Result> handleCustomException(CustomException e) {
        log.warn("業務例外 code={} msg={}", e.getCode(), e.getMessage());

        int status;
        try {
            status = Integer.parseInt(e.getCode());
        } catch (NumberFormatException nfe) {
            status = 400;
        }

        if (status < 100 || status > 599) {
            status = 400;
        }

        return ResponseEntity.status(status).body(Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.status(400).body(Result.error("400", message));
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private boolean containsIgnoreCase(String source, String fragment) {
        return source != null && fragment != null && source.toLowerCase().contains(fragment.toLowerCase());
    }
}
