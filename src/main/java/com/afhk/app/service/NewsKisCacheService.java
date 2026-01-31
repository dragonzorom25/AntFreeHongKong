package com.afhk.app.service;

import com.afhk.app.entity.NewsKisCacheEntity;
import com.afhk.app.repository.NewsKisCacheRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NewsKisCacheService {
    private static final Logger log = LoggerFactory.getLogger(NewsKisCacheService.class);

    @Value("${kis.api.base-url}") private String baseUrl;
    @Value("${kis.api.app-key}") private String appKey;
    @Value("${kis.api.app-secret}") private String appSecret;

    private final NewsKisCacheRepository repository;
    private final DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private volatile String accessToken = null;
    private long lastTokenTime = 0;
    private long lastTokenFailTime = 0;

    public NewsKisCacheService(NewsKisCacheRepository repository) {
        this.repository = repository;
    }

    public String getAccessToken() {
        return this.accessToken;
    }

    /** 🚀 [통합 수집] 뉴스 수집 및 저장 (구글 연동 URL 생성) */
    @Transactional
    public void collectAndSaveAll() {
        JsonNode output = fetchLatestNewsJson();
        if (output == null || !output.isArray()) return;

        int totalCount = output.size();
        int savedCount = 0;
        int skippedCount = 0;

        for (JsonNode node : output) {
            String title = node.path("hts_tltl").asText().trim();
            if (title.isEmpty()) title = node.path("hts_pbnt_titl_cntt").asText().trim();
            if (title.isEmpty()) continue;

            // 🚩 [중복 방어] 제목이 이미 존재하면 바로 스킵 (DB UNIQUE 제약 조건 대응)
            if (repository.existsByTitle(title)) {
                skippedCount++;
                continue;
            }

            String stockCode = node.path("rltm_iscd").asText().trim();
            String owner = node.path("dorg").asText().trim(); // KIS에서는 보통 뉴스 제공처(owner)
            String regTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            
            // 🚩 [수정] link 컬럼에 구글 검색 URL을 생성해서 저장
            String googleLink = generateGoogleSearchUrl(title, owner);

            try {
                NewsKisCacheEntity entity = new NewsKisCacheEntity(
                    title, 
                    googleLink, // 이제 DB link 컬럼에는 https://www.google.com/... 이 저장됩니다.
                    stockCode, 
                    owner, 
                    regTime, 
                    LocalDateTime.now(), 
                    title.contains("특징주") ? "GOLDEN" : "NORMAL", 
                    "ACTIVE"
                );
                repository.save(entity);
                savedCount++;
            } catch (Exception e) {
                log.error("🚨 DB 저장 에러 (중복 가능성): {}", e.getMessage());
            }
        }
        log.info("📊 [KIS 뉴스] 수신: {}건, 신규: {}건, 중복제외: {}건", totalCount, savedCount, skippedCount);
    }

    /** 🚩 검색어 조합 및 구글 URL 생성기 */
    private String generateGoogleSearchUrl(String title, String owner) {
        try {
            // 검색어 정제: HTML 태그 제거 및 특수문자 처리
            String cleanTitle = title.replaceAll("<[^>]*>?", "").trim();
            
            // 종목명(owner)이 유효한 경우 조합 ("종목명 제목"), 아니면 제목만
            String searchQuery = (owner != null && !owner.isEmpty() && !owner.equals("KIS") && !owner.equals("정보"))
                    ? owner + " " + cleanTitle
                    : cleanTitle;

            return "https://www.google.com/search?q=" + URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "https://www.google.com/search?q=" + URLEncoder.encode(title, StandardCharsets.UTF_8);
        }
    }

    private JsonNode fetchLatestNewsJson() {
        try {
            RestTemplate rt = new RestTemplate();
            long currentTime = System.currentTimeMillis();
            
            synchronized (this) {
                if (this.accessToken == null && (currentTime - lastTokenFailTime < 65000)) {
                    log.warn("⏳ KIS 토큰 발급 제한 대기 중...");
                    return null;
                }

                if (this.accessToken == null || (currentTime - lastTokenTime > 3600000)) {
                    String newToken = fetchNewToken(rt);
                    if (newToken != null) {
                        this.accessToken = newToken;
                        this.lastTokenTime = currentTime;
                        this.lastTokenFailTime = 0;
                    } else {
                        this.lastTokenFailTime = currentTime;
                        return null;
                    }
                }
            }

            String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String nowTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));

            String url = UriComponentsBuilder.fromUriString(baseUrl + "/uapi/domestic-stock/v1/quotations/news-title")
                    .queryParam("FID_NEWS_OFER_ENTP_CODE", "")
                    .queryParam("FID_COND_MRKT_CLS_CODE", "")
                    .queryParam("FID_INPUT_ISCD", "")
                    .queryParam("FID_TITL_CNTT", "")
                    .queryParam("FID_INPUT_DATE_1", today)
                    .queryParam("FID_INPUT_HOUR_1", nowTime)
                    .queryParam("FID_RANK_SORT_CLS_CODE", "0")
                    .queryParam("FID_INPUT_SRNO", "")
                    .build(true).toUriString();

            HttpHeaders h = new HttpHeaders();
            h.set("authorization", "Bearer " + accessToken);
            h.set("appkey", appKey);
            h.set("appsecret", appSecret);
            h.set("tr_id", "FHKST01011800");
            h.set("custtype", "P");
            h.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<JsonNode> response = rt.exchange(url, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
            if (response.getBody() != null && "0".equals(response.getBody().path("rt_cd").asText())) {
                return response.getBody().path("output");
            }
        } catch (Exception e) {
            log.error("🚨 KIS API 통신 에러: {}", e.getMessage());
        }
        return null;
    }

    private String fetchNewToken(RestTemplate rt) {
        try {
            String tokenUrl = baseUrl + "/oauth2/tokenP";
            Map<String, String> body = new HashMap<>();
            body.put("grant_type", "client_credentials");
            body.put("appkey", appKey);
            body.put("appsecret", appSecret);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<JsonNode> response = rt.postForEntity(tokenUrl, new HttpEntity<>(body, headers), JsonNode.class);
            if (response.getBody() != null && response.getBody().has("access_token")) {
                log.info("🔑 KIS 새 토큰 발급 성공");
                return response.getBody().get("access_token").asText();
            }
        } catch (Exception e) {
            log.error("🚨 토큰 발급 실패: {}", e.getMessage());
        }
        return null;
    }

    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        Sort sort = Sort.by(Sort.Direction.DESC, "rawDate");
        Map<String, Object> res = new HashMap<>();

        // 🚩 전광판 모드면 닥치고 5건만 리턴!
        if ("dashboard".equalsIgnoreCase(mode)) {
            List<Map<String, Object>> content = repository.findAll(sort).stream()
                    .limit(5) 
                    .map(this::convertToMap)
                    .collect(Collectors.toList());
            res.put("content", content);
            return res;
        }

        List<NewsKisCacheEntity> entities;
        if (search != null && !search.trim().isEmpty() && !search.equals("1")) {
            entities = repository.findByTitleContainingIgnoreCaseOrStockCodeContainingIgnoreCase(search, search, sort);
        } else {
            entities = repository.findAll(sort);
        }

        List<Map<String, Object>> content = entities.stream().map(this::convertToMap).collect(Collectors.toList());
        return applyPagination(content, page, size, mode, pagination);
    }

    private Map<String, Object> convertToMap(NewsKisCacheEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId()); m.put("title", e.getTitle()); m.put("link", e.getLink());
        m.put("stockName", e.getOwner()); m.put("stockCode", e.getStockCode());
        m.put("regDate", e.getRawDate().format(displayFormatter));
        m.put("serverStatus", calculateServerStatus(e.getRawDate()));
        m.put("featureOption", e.getFeatureOption());
        return m;
    }

    private String calculateServerStatus(LocalDateTime d) {
        if (d == null) return "-";
        long days = ChronoUnit.DAYS.between(d.toLocalDate(), LocalDateTime.now().toLocalDate());
        return (days == 0) ? "오늘" : days + "일 전";
    }

    private Map<String, Object> applyPagination(List<Map<String, Object>> l, int p, int s, String m, boolean pag) {
        Map<String, Object> res = new HashMap<>();
        int total = l.size();
        if (!pag || "client".equalsIgnoreCase(m)) {
            res.put("content", l); res.put("totalElements", total); return res;
        }
        int start = Math.min(p * s, total);
        int end = Math.min(start + s, total);
        res.put("content", l.subList(start, end));
        res.put("totalElements", total);
        res.put("totalPages", (int) Math.ceil((double) total / s));
        return res;
    }
}