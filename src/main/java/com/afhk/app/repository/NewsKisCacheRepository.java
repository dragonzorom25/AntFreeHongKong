package com.afhk.app.repository;

import com.afhk.app.entity.NewsKisCacheEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

public interface NewsKisCacheRepository extends JpaRepository<NewsKisCacheEntity, Long> {
    
    // 🚩 [기존] 링크 기반 중복 체크
    boolean existsByLink(String link);

    // 🚩 [신규 추가] 제목 기반 중복 체크 (서버 재기동 시 방어용)
    boolean existsByTitle(String title);

    List<NewsKisCacheEntity> findTop8ByOrderByRawDateDesc();

    @Modifying
    @Transactional
    void deleteByRawDateBefore(LocalDateTime dateTime);

    /** * ✅ [추가] 네이버식 검색을 위한 메서드 
     * 제목(Title)이나 종목코드(StockCode)로 대소문자 무시하고 검색합니다.
     */
    List<NewsKisCacheEntity> findByTitleContainingIgnoreCaseOrStockCodeContainingIgnoreCase(
            String title, String stockCode, Sort sort);
}