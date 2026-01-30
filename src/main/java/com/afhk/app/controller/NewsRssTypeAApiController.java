package com.afhk.app.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.afhk.app.service.NewsRssTypeAService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/newsRssTypeAList")
public class NewsRssTypeAApiController {

    private final NewsRssTypeAService service;

    public NewsRssTypeAApiController(NewsRssTypeAService service) {
        this.service = service;
    }

    /** 🔍 리스트 조회 */
    @GetMapping
    public Map<String, Object> getList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "server") String mode,
            @RequestParam(defaultValue = "true") boolean pagination
    ) {
        return service.getList(page, size, search, mode, pagination);
    }


}
