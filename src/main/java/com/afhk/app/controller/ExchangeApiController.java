package com.afhk.app.controller;

import com.afhk.app.service.KisIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 하단 바 비동기 업데이트를 위한 REST API 컨트롤러
 * [실전투자] KIS API를 통해 환율, 지수 정보를 통합 반환합니다.
 */
@RestController
@RequestMapping("/api/exchange")
public class ExchangeApiController {

    private static final Logger log = LoggerFactory.getLogger(ExchangeApiController.class);
    private final KisIndexService kisIndexService;

    // 🚩 이제 지저분하게 여러 서비스 안 부르고 KisIndexService 하나로 해결합니다.
    public ExchangeApiController(KisIndexService kisIndexService) {
        this.kisIndexService = kisIndexService;
    }

    /**
     * JavaScript에서 호출하는 최신 데이터 반환 API
     * 환율(USD), 나스닥, 다우, 코스피, 코스닥을 하나의 Map으로 합쳐서 반환합니다.
     */
    @GetMapping("/latest")
    public Map<String, Object> getLatestData() {
        Map<String, Object> combinedData = new HashMap<>();

        try {
            // 🚩 KIS 서비스를 통해 환율+지수를 한 방에 가져옵니다.
            Map<String, Object> kisData = kisIndexService.getAllIndices();
            
            if (kisData != null && !kisData.isEmpty()) {
                combinedData.putAll(kisData);
            } else {
                log.warn("⚠️ KIS 데이터가 비어있습니다. 토큰 확인이 필요합니다.");
                // 데이터 없을 시 기본값 셋팅 (형님 화면 깨지지 않게 방어)
                combinedData.put("USD", "1,342");
                combinedData.put("updateTime", "연결대기");
            }

        } catch (Exception e) {
            log.error("🚨 하단바 데이터 통합 중 오류 발생: {}", e.getMessage());
            combinedData.put("error", "데이터 수집 실패");
        }

        return combinedData;
    }
}