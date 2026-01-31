package com.afhk.app.scheduler;

import com.afhk.app.service.NewsDartTypeAService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

@Component
public class NewsDartScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsDartScheduler.class);

    @Autowired
    private NewsDartTypeAService dartService;

    /**
     * ✅ [사나이의 조절판] 아래 숫자들을 형님 마음대로 수정하세요!
     */
    private final int PEAK_INTERVAL = 1;      // 장중(집중 시간) 실행 주기 (분)
    private final int EVENING_INTERVAL = 10;  // 저녁 시간 실행 주기 (분)
    private final int NIGHT_INTERVAL = 30;    // 새벽 시간 실행 주기 (분)
    private final int WEEKEND_INTERVAL = 60;  // 주말 실행 주기 (분)

    @Scheduled(fixedDelay = 60000) // 1분마다 스케줄러가 조건을 체크합니다.
    public void run() {
        LocalTime now = LocalTime.now();
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        // 1. [주말 세팅] 토요일, 일요일은 데이터가 거의 없으므로 60분에 한 번!
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            if (now.getMinute() % WEEKEND_INTERVAL != 0) return;
        } 
        
        // 2. [평일 시간대별 세팅]
        else {
            // 새벽 (00:00 ~ 07:29) -> 30분에 한 번
            if (now.isBefore(LocalTime.of(7, 30))) {
                if (now.getMinute() % NIGHT_INTERVAL != 0) return;
            }
            // 저녁 (18:00 ~ 23:59) -> 형님 요청대로 10분에 한 번
            else if (now.isAfter(LocalTime.of(18, 0))) {
                if (now.getMinute() % EVENING_INTERVAL != 0) return;
            }
            // 장중/피크 (07:30 ~ 18:00) -> 1분에 한 번 (빡세게!)
            else {
                if (now.getMinute() % PEAK_INTERVAL != 0) return;
            }
        }

        try {
            log.info("🚀 [DART 운영] {} 실행 중... (주기: {}분)", now, getActiveInterval(now, dayOfWeek));
            dartService.collectAndSave();
        } catch (Exception e) {
            log.error("🚨 DART 스케줄러 에러: {}", e.getMessage());
        }
    }

    // 로그 표시용 헬퍼 함수
    private int getActiveInterval(LocalTime now, DayOfWeek day) {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return WEEKEND_INTERVAL;
        if (now.isBefore(LocalTime.of(7, 30))) return NIGHT_INTERVAL;
        if (now.isAfter(LocalTime.of(18, 0))) return EVENING_INTERVAL;
        return PEAK_INTERVAL;
    }
}