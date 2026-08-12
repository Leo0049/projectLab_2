package com.example.demo.exception;

import lombok.Data;

@Data
public class CustomException extends RuntimeException {
    private String code;
    private String msg;
    public CustomException(String code, String msg) {
        super(msg); // 把訊息傳給父類 RuntimeException
        this.code=code;
        this.msg=msg;
    }
    public CustomException(String msg) {
        this.code="500";
        this.msg=msg;
    }


}
