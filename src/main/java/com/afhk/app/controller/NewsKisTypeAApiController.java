package com.afhk.app.controller;

import com.afhk.app.service.NewsKisCacheService; // ✅ 통합 서비스로 정확히 변경
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/newsKisTypeAList")
public class NewsKisTypeAApiController {

    private final NewsKisCacheService service; // ✅ 통합 서비스 주입

    public NewsKisTypeAApiController(NewsKisCacheService service) {
        this.service = service;
    }

    /** * 🔍 KIS 뉴스 리스트 조회 
     * 네이버 컨트롤러와 동일한 규격으로 page, size, search 파라미터를 처리합니다.
     */
    @GetMapping
    public Map<String, Object> getList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "server") String mode,
            @RequestParam(defaultValue = "true") boolean pagination
    ) {
        // ✅ 이제 통합 서비스의 getList()를 호출하여 
        // 페이징, 검색, 그리고 형님 엔티티 규격에 맞춘 데이터를 반환합니다.
        return service.getList(page, size, search, mode, pagination);
    }
}