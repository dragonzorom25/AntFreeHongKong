package com.afhk.app.service;

import com.afhk.app.entity.NewsIntegratedEntity;
import com.afhk.app.repository.NewsIntegratedRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NewsNaverTypeAService {

    private static final Logger log = LoggerFactory.getLogger(NewsNaverTypeAService.class);
    private final NewsIntegratedRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeFormatter naverDateFormatter = DateTimeFormatter.RFC_1123_DATE_TIME;
    private final DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${python.stock.json.path}")
    private String script_json_path;

    private final List<String> MAJOR_KEYWORDS = Arrays.asList(
            "수주", "공급계약", "흑자전환", "공시", "M&A", "MOU", "투자",
            "상한가", "특징주", "독점", "유상증자", "국책과제", "무상증자", "인수", "단일판매",
            "상승", "돌파", "최고치", "실적개선", "사상최대", "급등", "신고가", "강세", "지분매각", "계약체결", "정부정책"
    );

    @Autowired
    public NewsNaverTypeAService(NewsIntegratedRepository repository) {
        this.repository = repository;
    }

    /** ✅ 화면 조회: 공통 테이블에서 NAVER 타입만 필터링 */
    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        try {
            repository.deleteByRawDateBefore(LocalDateTime.now().minusDays(3));
        } catch (Exception e) {
            log.error("🧹 삭제 중 에러: {}", e.getMessage());
        }

        List<NewsIntegratedEntity> entities;
        Sort sort = Sort.by(Sort.Direction.DESC, "rawDate");

        if (search != null && !search.trim().isEmpty() && !search.equals("1")) {
            entities = repository.findByNewsTypeAndTitleContainingIgnoreCaseOrNewsTypeAndStockNameContainingIgnoreCase(
                    "NAVER", search, "NAVER", search, sort);
        } else {
            entities = repository.findByNewsType("NAVER", sort);
        }

        List<Map<String, Object>> content = entities.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());

        return applyPagination(content, page, size, mode, pagination);
    }

    /** ✅ 수집 엔진: 네이버 뉴스 수집 (형님 요청 200ms 기본 / 429 감지 시 2000ms 딜레이 적용) */
    public void collectAndSaveAll() {
        log.info("🚀 네이버 뉴스 수집 엔진 가동 (기본 200ms / 방어 2000ms)");
        List<String> stockMaster = getStockMasterFromJson();

        for (String word : MAJOR_KEYWORDS) {
            try {
                // 🚩 기본 호출 사이 간격 0.2초
                Thread.sleep(200); 

                String url = UriComponentsBuilder.fromUriString("https://openapi.naver.com/v1/search/news.json")
                        .queryParam("query", word)
                        .queryParam("display", 50)
                        .queryParam("sort", "date")
                        .build().toUriString();

                HttpHeaders h = new HttpHeaders();
                h.set("X-Naver-Client-Id", "FVzkwJZt2usCrma3m5by");
                h.set("X-Naver-Client-Secret", "CnkokvjlJB");

                ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class);
                
                if (res.getStatusCode() == HttpStatus.OK) {
                    JsonNode items = objectMapper.readTree(res.getBody()).path("items");

                    for (JsonNode item : items) {
                        String link = item.path("link").asText();
                        String rawTitle = item.path("title").asText();
                        String cleanTitle = rawTitle.replaceAll("<[^>]*>", "")
                                                    .replace("&quot;", "\"")
                                                    .replace("&amp;", "&")
                                                    .replace("&#39;", "'")
                                                    .replace("&lt;", "<")
                                                    .replace("&gt;", ">");

                        if (repository.existsByLink(link) || repository.existsByTitle(cleanTitle)) {
                            continue; 
                        }

                        try {
                            LocalDateTime pubDate = LocalDateTime.parse(item.path("pubDate").asText(), naverDateFormatter);
                            String stockName = extractStockName(cleanTitle, stockMaster);
                            String finalName = (stockName != null && !stockName.isEmpty()) ? stockName : "네이버뉴스";
                            String code = findStockCodeByName(finalName, stockMaster);

                            repository.save(new NewsIntegratedEntity(
                                    code, finalName, cleanTitle, link, pubDate,
                                    findMatchedKeyword(cleanTitle), calculateServerStatus(pubDate), "NAVER" 
                            ));
                        } catch (Exception e) {
                            log.error("🚨 개별 뉴스 저장 에러: {}", e.getMessage());
                        }
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("🚨 수집 스레드 중단됨");
                break;
            } catch (Exception e) {
                // 🚩 [핵심] 429 에러 발생 시 형님 말씀대로 2초(2000ms) 완전 정지!
                if (e.getMessage().contains("429")) {
                    log.warn("⏳ 속도 제한(429) 감지! 2초간 완전 정지 후 재개합니다... 키워드: [{}]", word);
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                } else {
                    log.error("⚠️ 키워드 [{}] 수집 에러: {}", word, e.getMessage());
                }
            }
        }
        log.info("✅ 네이버 뉴스 수집 종료");
    }

    private List<String> getStockMasterFromJson() {
        try {
            File f = new File(script_json_path);
            if (!f.exists()) return new ArrayList<>();
            JsonNode root = objectMapper.readTree(f);
            List<String> list = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) {
                    if (n.has("Name")) list.add(n.get("Name").asText().trim());
                }
            }
            list.sort((a, b) -> Integer.compare(b.length(), a.length()));
            return list;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private String findStockCodeByName(String name, List<String> master) {
        if (name == null || name.equals("네이버뉴스")) return "";
        try {
            JsonNode root = objectMapper.readTree(new File(script_json_path));
            for (JsonNode n : root) {
                if (n.get("Name").asText().replace(" ", "").equalsIgnoreCase(name.replace(" ", ""))) {
                    return n.get("Code").asText().trim();
                }
            }
        } catch (Exception e) { }
        return "";
    }

    private String extractStockName(String title, List<String> master) {
        if (title == null || master == null) return "";
        String t = title.replaceAll("[^가-힣a-zA-Z0-9]", "").toUpperCase();
        for (String s : master) {
            if (t.contains(s.toUpperCase().replace(" ", ""))) return s;
        }
        return "";
    }

    private String findMatchedKeyword(String title) {
        return MAJOR_KEYWORDS.stream().filter(title::contains).findFirst().orElse("재료");
    }

    private String calculateServerStatus(LocalDateTime d) {
        if (d == null) return "-";
        long days = ChronoUnit.DAYS.between(d.toLocalDate(), LocalDateTime.now().toLocalDate());
        return (days == 0) ? "오늘" : days + "일 전";
    }

    private Map<String, Object> convertToMap(NewsIntegratedEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("title", e.getTitle());
        m.put("link", e.getLink());
        m.put("stockName", e.getStockName());
        m.put("stockCode", e.getStockCode());
        m.put("regDate", e.getRawDate().format(displayFormatter));
        m.put("serverStatus", calculateServerStatus(e.getRawDate()));
        m.put("featureOption", e.getFeatureOption());
        m.put("newsType", e.getNewsType()); 
        return m;
    }

    private Map<String, Object> applyPagination(List<Map<String, Object>> l, int p, int s, String m, boolean pag) {
        Map<String, Object> res = new HashMap<>();
        int total = l.size();
        if (!pag || "client".equalsIgnoreCase(m)) {
            res.put("content", l);
            res.put("totalElements", total);
            return res;
        }
        int start = Math.min(p * s, total);
        int end = Math.min(start + s, total);
        res.put("content", l.subList(start, end));
        res.put("totalElements", total);
        res.put("totalPages", (int) Math.ceil((double) total / s));
        return res;
    }
}