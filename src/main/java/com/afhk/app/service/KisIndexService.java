package com.afhk.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class KisIndexService {
    private static final Logger log = LoggerFactory.getLogger(KisIndexService.class);

    @Value("${kis.api.base-url}") private String baseUrl;
    @Value("${kis.api.app-key}") private String appKey;
    @Value("${kis.api.app-secret}") private String appSecret;

    private final NewsKisCacheService newsService;

    public KisIndexService(NewsKisCacheService newsService) {
        this.newsService = newsService;
    }

    public Map<String, Object> getAllIndices() {
        Map<String, Object> result = new HashMap<>();
        String token = newsService.getAccessToken();

        if (token == null) return result;

        // 🚩 지수 정보만 남기고 환율은 과감히 제거!
        fetchDomestic(token, "0001", "KOSPI", result);
        fetchDomestic(token, "1001", "KOSDAQ", result);
        fetchOverseas(token, "NAS", "IXIC", "NASDAQ", result);
        fetchOverseas(token, "DJS", "DJI", "DOW", result);

        result.put("updateTime", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        return result;
    }

    private void fetchDomestic(String token, String code, String key, Map<String, Object> res) {
        try {
            RestTemplate rt = new RestTemplate();
            String url = UriComponentsBuilder.fromUriString(baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-index-price")
                    .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                    .queryParam("FID_INPUT_ISCD", code)
                    .build().toUriString();
            
            ResponseEntity<JsonNode> resp = rt.exchange(url, HttpMethod.GET, new HttpEntity<>(getHeaders(token, "FHKST01010100")), JsonNode.class);
            JsonNode out = resp.getBody().path("output");
            if (!out.isMissingNode()) {
                res.put(key, out.path("bstp_nmix_prpr").asText());
                String sign = out.path("prdy_vrss_sign").asText();
                res.put(key + "_TYPE", (sign.equals("1") || sign.equals("2")) ? "up" : "down");
            }
        } catch (Exception e) { log.error("🚨 {} 지수 수집 실패", key); }
    }

    private void fetchOverseas(String token, String excd, String symb, String key, Map<String, Object> res) {
        try {
            RestTemplate rt = new RestTemplate();
            String url = UriComponentsBuilder.fromUriString(baseUrl + "/uapi/overseas-price/v1/quotations/price")
                    .queryParam("AUTH", "")
                    .queryParam("EXCD", excd)
                    .queryParam("SYMB", symb)
                    .build().toUriString();
            
            ResponseEntity<JsonNode> resp = rt.exchange(url, HttpMethod.GET, new HttpEntity<>(getHeaders(token, "HHDFS76200200")), JsonNode.class);
            JsonNode out = resp.getBody().path("output");
            if (!out.isMissingNode()) {
                res.put(key, out.path("last").asText());
                double diff = out.path("diff").asDouble(0);
                res.put(key + "_TYPE", diff >= 0 ? "up" : "down");
            }
        } catch (Exception e) { log.error("🚨 {} 지수 수집 실패", key); }
    }

    private HttpHeaders getHeaders(String token, String trId) {
        HttpHeaders h = new HttpHeaders();
        h.set("authorization", "Bearer " + token);
        h.set("appkey", appKey);
        h.set("appsecret", appSecret);
        h.set("tr_id", trId);
        h.set("custtype", "P");
        return h;
    }
}