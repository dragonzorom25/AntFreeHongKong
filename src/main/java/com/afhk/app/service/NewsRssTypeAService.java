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
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NewsRssTypeAService {

    private static final Logger log = LoggerFactory.getLogger(NewsRssTypeAService.class);
    private final NewsIntegratedRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper(); 
    private final DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${python.stock.json.path}")
    private String script_json_path;

    private final List<Map<String, String>> RSS_SOURCES = Arrays.asList(
        Map.of("name", "연합뉴스", "url", "https://www.yonhapnewstv.co.kr/browse/feed/"),
        Map.of("name", "매일경제", "url", "https://www.mk.co.kr/rss/30200030/"),
        Map.of("name", "한국경제", "url", "https://www.hankyung.com/feed/finance"),
        Map.of("name", "머니투데이", "url", "https://rss.mt.co.kr/mt_news.xml"),
        Map.of("name", "파이낸셜뉴스", "url", "https://www.fnnews.com/rss/r20/fn_realnews_stock.xml"),
        Map.of("name", "서울경제", "url", "https://www.sedaily.com/rss/finance"),
        Map.of("name", "아시아경제", "url", "https://www.asiae.co.kr/rss/stock.htm"),
        Map.of("name", "헤럴드경제", "url", "https://biz.heraldcorp.com/rss/google/finance"),
        Map.of("name", "뉴시스속보", "url", "https://www.newsis.com/RSS/sokbo.xml"),
        Map.of("name", "뉴시스금융", "url", "https://www.newsis.com/RSS/bank.xml")
    );

    private final List<String> POSITIVE_KEYWORDS = Arrays.asList(
        "상승", "돌파", "수주", "공급계약", "최고치", "흑자전환", "실적개선", "사상최대", "영업익 증", "매출 증", "서프라이즈",
        "M&A", "인수", "독점", "특허", "임상", "승인", "양해각서", "MOU", "협력", "파트너십", "제휴",
        "급등", "상한가", "신고가", "증설", "강세", "반등", "질주", "훈풍", "유입", "순매수", "상향", "추천",
        "신기술", "상용화", "국산화", "최초", "IPO", "상장", "액면분할", "무상증자", "배당", "특징주"
    );

    @Autowired
    public NewsRssTypeAService(NewsIntegratedRepository repository) {
        this.repository = repository;
    }

    /** ✅ [화면 조회] 오직 DB 데이터만 리턴 (속도 최우선) */
    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        try {
            repository.deleteByRawDateBefore(LocalDateTime.now().minusDays(3));
        } catch (Exception e) {
            log.error("🧹 RSS 데이터 삭제 중 에러: {}", e.getMessage());
        }

        List<NewsIntegratedEntity> entities;
        Sort sort = Sort.by(Sort.Direction.DESC, "rawDate");

        if (search != null && !search.trim().isEmpty() && !search.equals("1")) {
            entities = repository.findByNewsTypeAndTitleContainingIgnoreCaseOrNewsTypeAndStockNameContainingIgnoreCase(
                    "RSS", search, "RSS", search, sort);
        } else {
            entities = repository.findByNewsType("RSS", sort);
        }

        List<Map<String, Object>> content = entities.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());

        return applyPagination(content, page, size, mode, pagination);
    }

    /** ✅ [수집 전용] 스케줄러가 호출할 메서드 */
    public void collectAndSaveAll() {
        log.info("🚀 RSS 통합 뉴스 수집 엔진 가동...");
        List<String> stockMaster = getStockMasterFromJson();
        
        for (Map<String, String> source : RSS_SOURCES) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("User-Agent", "Mozilla/5.0");
                ResponseEntity<byte[]> response = restTemplate.exchange(source.get("url"), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
                if (response.getBody() == null) continue;

                DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                Document doc = builder.parse(new ByteArrayInputStream(response.getBody()));
                NodeList items = doc.getElementsByTagName("item");

                int savedCount = 0;
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    String title = getTagValue("title", item);
                    String link = getTagValue("link", item);
                    String matchedKeyword = findMatchedKeyword(title);

                    // 🚩 [중복 방어] 링크 또는 제목이 이미 있으면 저장하지 않고 즉시 스킵 (서버 재기동 시 데이터 중복 방지)
                    if (repository.existsByLink(link) || repository.existsByTitle(title)) {
                        continue;
                    }

                    String stockName = extractStockName(title, stockMaster);
                    
                    // 종목명이 있거나 핵심 키워드가 포함된 경우만 저장
                    if (!stockName.isEmpty() || matchedKeyword != null) {
                        String stockCode = (!stockName.isEmpty()) ? findStockCodeByName(stockName) : "";
                        String finalStockName = (!stockName.isEmpty()) ? stockName : source.get("name");
                        LocalDateTime now = LocalDateTime.now();

                        repository.save(new NewsIntegratedEntity(
                            stockCode, finalStockName, title, link, now, 
                            (matchedKeyword != null ? matchedKeyword : "정보"), 
                            calculateServerStatus(now), "RSS"
                        ));
                        savedCount++;
                    }
                }
                if(savedCount > 0) log.info("💡 [{}] RSS 새 뉴스 {}건 저장", source.get("name"), savedCount);
            } catch (Exception e) {
                log.error("⚠️ [{}] RSS 수집 중 에러: {}", source.get("name"), e.getMessage());
            }
        }
        log.info("✅ RSS 통합 뉴스 수집 완료");
    }

    private List<String> getStockMasterFromJson() {
        try {
            File jsonFile = new File(script_json_path);
            if (!jsonFile.exists()) return new ArrayList<>();
            JsonNode root = objectMapper.readTree(jsonFile);
            List<String> stockList = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.has("Name")) stockList.add(node.get("Name").asText().trim());
                }
            }
            stockList.sort((a, b) -> Integer.compare(b.length(), a.length()));
            return stockList;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private String findStockCodeByName(String stockName) {
        if (stockName == null || stockName.isEmpty()) return "";
        try {
            JsonNode root = objectMapper.readTree(new File(script_json_path));
            String target = stockName.replace(" ", "").toUpperCase();
            for (JsonNode node : root) {
                if (node.get("Name").asText().replace(" ", "").equalsIgnoreCase(target)) {
                    return node.get("Code").asText().trim();
                }
            }
        } catch (Exception e) { }
        return "";
    }

    private String extractStockName(String title, List<String> stockMaster) {
        if (title == null || stockMaster == null) return "";
        String cleanTitle = title.replaceAll("[^가-힣a-zA-Z0-9]", "").toUpperCase();
        for (String stock : stockMaster) {
            if (cleanTitle.contains(stock.toUpperCase().replace(" ", ""))) return stock;
        }
        return "";
    }

    private String calculateServerStatus(LocalDateTime rawDate) {
        if (rawDate == null) return "-";
        long days = ChronoUnit.DAYS.between(rawDate.toLocalDate(), LocalDateTime.now().toLocalDate());
        return (days == 0) ? "오늘" : days + "일 전";
    }

    private String findMatchedKeyword(String title) {
        if (title == null) return null;
        return POSITIVE_KEYWORDS.stream().filter(title::contains).findFirst().orElse(null);
    }

    private String getTagValue(String tag, Element element) {
        NodeList nlList = element.getElementsByTagName(tag);
        if (nlList.getLength() > 0 && nlList.item(0).hasChildNodes()) {
            return nlList.item(0).getFirstChild().getNodeValue().trim();
        }
        return "";
    }

    private Map<String, Object> convertToMap(NewsIntegratedEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("title", entity.getTitle());
        map.put("link", entity.getLink());
        map.put("stockName", entity.getStockName());
        map.put("stockCode", entity.getStockCode());
        map.put("regDate", entity.getRawDate().format(displayFormatter));
        map.put("serverStatus", calculateServerStatus(entity.getRawDate()));
        map.put("featureOption", entity.getFeatureOption());
        return map;
    }

    private Map<String, Object> applyPagination(List<Map<String, Object>> list, int page, int size, String mode, boolean pagination) {
        Map<String, Object> result = new HashMap<>();
        int total = list.size();
        if (!pagination || "client".equalsIgnoreCase(mode)) {
            result.put("content", list);
            result.put("totalElements", total);
            return result;
        }
        int start = Math.min(page * size, total);
        int end = Math.min(start + size, total);
        result.put("content", list.subList(start, end));
        result.put("totalElements", total);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        return result;
    }
}