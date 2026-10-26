package com.example.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// scratch: 只用於還原驗證，不屬於 PR 分支
@RestController
public class ScratchRouteController {
    @GetMapping("/api/scratch-undocumented")
    public String undocumented() {
        return "x";
    }
}
