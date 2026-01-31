package com.afhk.app.scheduler;

import com.afhk.app.service.NewsNaverTypeAService;
import com.afhk.app.service.NewsRssTypeAService;
import com.afhk.app.service.NewsKisCacheService; // 🚩 서비스 변경
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NewsTotalScheduler {
    private static final Logger log = LoggerFactory.getLogger(NewsTotalScheduler.class);
    
    private final NewsNaverTypeAService naverService;
    private final NewsRssTypeAService rssService;
    private final NewsKisCacheService kisCacheService; // 🚩 서비스 변경

    public NewsTotalScheduler(NewsNaverTypeAService naverService, 
                              NewsRssTypeAService rssService, 
                              NewsKisCacheService kisCacheService) { // 🚩 주입 변경
        this.naverService = naverService;
        this.rssService = rssService;
        this.kisCacheService = kisCacheService;
        log.info("🚀 [통합 뉴스 엔진] 빈 생성 완료 - 네이버/RSS/KIS 1분 주기로 통합 관리 시작!");
    }

    /**
     * ✅ 모든 뉴스 수집을 1분(60000ms)마다 순서대로 실행
     * 순서대로 실행해야 한투(KIS) API 차단을 완벽하게 방어합니다.
     */
    @Scheduled(initialDelay = 5000, fixedDelay = 60000) 
    public void runAllNewsCollection() {
        log.info("⏰ [통합 수집 엔진] 턴 시작: " + java.time.LocalTime.now());
        
        try {
            // 1. 네이버 뉴스 수집
            log.info("📰 네이버 수집 중...");
            naverService.collectAndSaveAll();
            
            // 2. RSS 뉴스 수집
            log.info("📡 RSS 수집 중...");
            rssService.collectAndSaveAll();
            
            // 3. KIS(한투) 실시간 뉴스 수집 (통합 서비스 호출)
            log.info("🌐 KIS 실시간 수집 중...");
            // 🚩 기존 kisApiService.getLatestDataFromDb() 대신 통합 서비스의 수집 로직 호출
            kisCacheService.collectAndSaveAll(); 

            log.info("✅ [통합 수집 완료] 모든 뉴스가 DB에 싱싱하게 꽂혔습니다.");
        } catch (Exception e) {
            log.error("🚨 통합 스케줄러 에러 발생: {}", e.getMessage(), e);
        }
    }
}