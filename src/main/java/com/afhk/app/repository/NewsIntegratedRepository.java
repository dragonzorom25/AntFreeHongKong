package com.afhk.app.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.afhk.app.entity.NewsIntegratedEntity;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsIntegratedRepository extends JpaRepository<NewsIntegratedEntity, Long> {

    // 🔍 검색용: 제목에 키워드 포함 여부 (기존 유지)
    Page<NewsIntegratedEntity> findByTitleContaining(String title, Pageable pageable);

    // 🚩 추가: 뉴스 타입(NAVER/RSS)별 조회 (통합 테이블의 핵심!)
    List<NewsIntegratedEntity> findByNewsType(String newsType, Sort sort);

    // 🚫 중복 체크용: 링크 또는 제목 존재 확인 (기존 유지)
    boolean existsByLink(String link);
    boolean existsByTitle(String title);

    // 🧹 청소용: 3일 이전 데이터 삭제 (삭제는 트랜잭션 필수)
    @Transactional
    void deleteByRawDateBefore(LocalDateTime dateTime);
}