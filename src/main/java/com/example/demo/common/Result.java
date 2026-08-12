package com.example.demo.common;

import lombok.Data;

@Data
public class Result {
    private String code;
    private String msg;
    private Object data;

    // --- 成功系列 ---

    public static Result success() {
        Result result = new Result();
        result.setCode("200");
        result.setMsg("請求成功");
        return result;
    }



    public static Result success(Object data) {
        Result result = success();
        result.setData(data);
        return result;
    }

    // --- 錯誤系列 ---

    public static Result error() {
        Result result = new Result();
        result.setCode("500");
        result.setMsg("系統錯誤");
        return result;
    }

    public static Result error(String code, String msg) {
        Result result = new Result();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }

    // 💡 關鍵新增：支援攜帶資料的錯誤方法（解決 201 補填手機號碼的問題）
    public static Result error(String code, String msg, Object data) {
        Result result = new Result();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }

    public static Result error(String msg) {
        Result result = new Result();
        result.setCode("500"); // 預設系統錯誤碼
        result.setMsg(msg);
        return result;
    }
}