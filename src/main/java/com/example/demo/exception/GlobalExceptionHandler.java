package com.example.demo.exception;

import com.example.demo.common.Result;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

        String devMsg = message != null ? message : e.getClass().getSimpleName();
        return ResponseEntity.status(500).body(Result.error("500", devMsg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleGeneralException(Exception e) {
        e.printStackTrace();
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return ResponseEntity.status(500).body(Result.error("500", msg));
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<Result> handleCustomException(CustomException e) {
        System.out.println("Caught CustomException: " + e.getMessage());

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
