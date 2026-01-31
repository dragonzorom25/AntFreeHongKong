package com.afhk.app.controller;

import com.afhk.app.service.NewsDartTypeAService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/newsDartTypeAList")
public class NewsDartTypeAApiController {

    private final NewsDartTypeAService service;

    // ✅ 생성자 주입을 더 명확하게 (Autowired 명시)
    @Autowired
    public NewsDartTypeAApiController(NewsDartTypeAService service) {
        this.service = service;
    }

    /** 🔍 DART 공시 리스트 조회 */
    @GetMapping
    public Map<String, Object> getList(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "15") int size,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "mode", defaultValue = "server") String mode,
            @RequestParam(name = "pagination", defaultValue = "true") boolean pagination
    ) {
        // ✅ 서비스에 정의된 getList(int, int, String, String, boolean) 호출
        return service.getList(page, size, search, mode, pagination);
    }
}