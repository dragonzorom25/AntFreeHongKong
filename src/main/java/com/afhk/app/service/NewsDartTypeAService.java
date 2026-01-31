package com.afhk.app.service;

import com.afhk.app.entity.NewsIntegratedEntity;
import com.afhk.app.repository.NewsIntegratedRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class NewsDartTypeAService {

    private static final Logger log = LoggerFactory.getLogger(NewsDartTypeAService.class);

    @Autowired
    private NewsIntegratedRepository repository; 

    @Value("${opendart.dart_api_key:}")
    private String API_KEY;

    @Value("${python.stock.json.path:}")
    private String script_json_path;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> profitStatusCache = new ConcurrentHashMap<>();
    private final DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final List<String> GOOD_KEYWORDS = Arrays.asList(
            "공급계약", "수주", "판매계약", "체결", "흑자전환",
            "영업이익증가", "무상증자", "자사주소각", "자사주취득", "인수", "합병", "단일판매"
    );

    /** ✅ 1. 리스트 조회 (정상 운영 모드) */
    @Transactional
    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        try {
            // 3일 지난 데이터 청소
            repository.deleteByRawDateBefore(LocalDateTime.now().minusDays(3));
        } catch (Exception e) {
            log.error("🧹 DART 청소 에러: {}", e.getMessage());
        }
        
        // 실시간 수집 호출
        collectAndSave();

        List<NewsIntegratedEntity> entities = repository.findByNewsType("DART", Sort.by(Sort.Direction.DESC, "rawDate"));
        
        List<Map<String, Object>> filtered = entities.stream()
            .filter(e -> {
                if (search == null || search.trim().isEmpty() || "1".equals(search)) return true;
                if ("3".equals(search)) return GOOD_KEYWORDS.stream().anyMatch(k -> e.getTitle().contains(k));
                String s = search.toLowerCase();
                return e.getTitle().toLowerCase().contains(s) || 
                       (e.getStockName() != null && e.getStockName().toLowerCase().contains(s));
            })
            .map(this::convertToMap)
            .collect(Collectors.toList());

        return applyPagination(filtered, page, size, mode, pagination);
    }

    /** ✅ 2. 데이터 수집 핵심 엔진 (실시간 날짜 적용) */
    public void collectAndSave() {
        LocalDate targetLocalDate = LocalDate.now();
        
        // 오전 7시 30분 이전이면 전날 데이터부터 훑기
        if (LocalTime.now().isBefore(LocalTime.of(7, 30))) {
            targetLocalDate = targetLocalDate.minusDays(1);
        }
        
        String targetDate = targetLocalDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int pageNo = 1;

        try {
            while (true) {
                String targetUrl = UriComponentsBuilder.fromUriString("https://opendart.fss.or.kr/api/list.json") 
                        .queryParam("crtfc_key", API_KEY)
                        .queryParam("bgnde", targetDate)
                        .queryParam("endde", targetDate)
                        .queryParam("page_no", pageNo)
                        .queryParam("page_count", "100").toUriString();

                String response = restTemplate.getForObject(targetUrl, String.class);
                if (response == null) break;

                JSONObject json = new JSONObject(response);
                if (!"000".equals(json.optString("status"))) break;

                JSONArray list = json.getJSONArray("list");
                if (list.length() == 0) break;

                for (int i = 0; i < list.length(); i++) {
                    JSONObject obj = list.getJSONObject(i);
                    String corpCls = obj.optString("corp_cls");
                    if (!Arrays.asList("Y", "K", "N").contains(corpCls)) continue;

                    String rcpNo = obj.optString("rcept_no");
                    String link = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rcpNo;
                    String title = obj.optString("report_nm");

                    // 🚩 [중복 방어] 링크(접수번호)가 이미 있거나, 제목이 완전히 똑같으면 스킵!
                    if (repository.existsByLink(link) || repository.existsByTitle(title)) {
                        continue;
                    }

                    String corpCode = obj.optString("corp_code");
                    String stockCode = obj.optString("stock_code");
                    String corpName = obj.optString("corp_name");

                    // 종목코드 매칭
                    if (stockCode == null || "null".equals(stockCode) || stockCode.isEmpty()) {
                        stockCode = findStockCodeFromJson(corpName);
                    }

                    String feature = "[재무미확인]";
                    if (GOOD_KEYWORDS.stream().anyMatch(title::contains)) {
                        feature = profitStatusCache.computeIfAbsent(corpCode, this::getProfitStatusFromDart);
                    }

                    repository.save(new NewsIntegratedEntity(
                            stockCode, corpName, title, link, LocalDateTime.now(), 
                            feature, getMarketName(corpCls), "DART"
                    ));
                }
                if (list.length() < 100) break;
                pageNo++;
                Thread.sleep(200); // API 부하 방지
            }
        } catch (Exception e) { log.error("🚨 DART 수집 에러: {}", e.getMessage()); }
    }

    private String findStockCodeFromJson(String corpName) {
        try {
            File file = new File(script_json_path);
            if (!file.exists()) return "";
            JsonNode root = objectMapper.readTree(file);
            for (JsonNode node : root) {
                if (node.get("Name").asText().replace(" ","").equalsIgnoreCase(corpName.replace(" ",""))) {
                    return node.get("Code").asText().trim();
                }
            }
        } catch (Exception e) {}
        return "";
    }

    private String getProfitStatusFromDart(String corpCode) {
        String url = "https://opendart.fss.or.kr/api/fnlttSinglAcnt.json";
        String currentYear = String.valueOf(LocalDate.now().getYear());
        String[] years = {currentYear, String.valueOf(Integer.parseInt(currentYear)-1)};
        String[][] reports = {{"11014", "3분기"}, {"11012", "반기"}, {"11013", "1분기"}, {"11011", "결산"}};
        for (String y : years) {
            for (String[] r : reports) {
                try {
                    String tUrl = UriComponentsBuilder.fromUriString(url).queryParam("crtfc_key", API_KEY)
                        .queryParam("corp_code", corpCode).queryParam("bsns_year", y).queryParam("reprt_code", r[0]).toUriString();
                    JSONObject json = new JSONObject(restTemplate.getForObject(tUrl, String.class));
                    if ("000".equals(json.optString("status")) && json.has("list")) {
                        return (Long.parseLong(json.getJSONArray("list").getJSONObject(0).optString("thstrm_amount").replace(",","")) > 0 ? "[흑자]" : "[적자]") + " ("+y+" "+r[1]+")";
                    }
                } catch (Exception e) {}
            }
        }
        return "[재무미확인]";
    }

    private String getMarketName(String cls) {
        return "Y".equals(cls) ? "코스피" : ("K".equals(cls) ? "코스닥" : ("N".equals(cls) ? "코넥스" : "기타"));
    }

    private Map<String, Object> convertToMap(NewsIntegratedEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId()); m.put("stockCode", e.getStockCode());
        m.put("stockName", e.getStockName()); m.put("title", e.getTitle());
        m.put("regDate", e.getRawDate().format(displayFormatter));
        m.put("featureOption", e.getFeatureOption()); m.put("link", e.getLink());
        m.put("newsType", e.getNewsType());
        return m;
    }

    private Map<String, Object> applyPagination(List<Map<String, Object>> list, int page, int size, String mode, boolean pagination) {
        Map<String, Object> res = new HashMap<>();
        int total = list.size();
        if (!pagination || "client".equalsIgnoreCase(mode)) {
            res.put("content", list); res.put("totalElements", total); return res;
        }
        int start = Math.min(page * size, total);
        int end = Math.min(start + size, total);
        res.put("content", list.subList(start, end));
        res.put("totalElements", total);
        res.put("totalPages", (int) Math.ceil((double) total / size));
        return res;
    }
}