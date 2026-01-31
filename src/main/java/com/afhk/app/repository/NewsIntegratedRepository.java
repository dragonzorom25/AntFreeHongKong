package com.afhk.app.repository;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.afhk.app.entity.NewsIntegratedEntity;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsIntegratedRepository extends JpaRepository<NewsIntegratedEntity, Long> {

    // ✅ 네이버 타입 내에서 제목 또는 종목명 검색
    List<NewsIntegratedEntity> findByNewsTypeAndTitleContainingIgnoreCaseOrNewsTypeAndStockNameContainingIgnoreCase(
        String type1, String title, String type2, String stockName, Sort sort);

    List<NewsIntegratedEntity> findByNewsType(String newsType, Sort sort);

    boolean existsByLink(String link);
    boolean existsByTitle(String title);

    @Transactional
    void deleteByRawDateBefore(LocalDateTime dateTime);
}