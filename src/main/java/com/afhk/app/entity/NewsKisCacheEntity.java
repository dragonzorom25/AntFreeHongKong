package com.afhk.app.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "news_kis_cache", schema = "mybaselink")
public class NewsKisCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500, nullable = false)
    private String title;

    @Column(length = 1000, nullable = false, unique = true)
    private String link; 

    @Column(name = "stock_code", length = 20)
    private String stockCode;

    @Column(length = 100)
    private String owner; 

    @Column(name = "reg_date", length = 50)
    private String regDate; 

    @Column(name = "raw_date", nullable = false)
    private LocalDateTime rawDate; 

    @Column(name = "server_status", length = 50)
    private String serverStatus;

    @Column(name = "feature_option", length = 100)
    private String featureOption;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public NewsKisCacheEntity() {}

    public NewsKisCacheEntity(String title, String link, String stockCode, String owner, 
                              String regDate, LocalDateTime rawDate, String featureOption, String serverStatus) {
        this.title = title;
        this.link = link;
        this.stockCode = stockCode;
        this.owner = owner;
        this.regDate = regDate;
        this.rawDate = rawDate;
        this.featureOption = featureOption;
        this.serverStatus = serverStatus;
    }

    // Getter
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getLink() { return link; }
    public String getStockCode() { return stockCode; }
    public String getOwner() { return owner; }
    public String getRegDate() { return regDate; }
    public LocalDateTime getRawDate() { return rawDate; }
    public String getFeatureOption() { return featureOption; }
    public String getServerStatus() { return serverStatus; }

    // ✅ [추가] 사나이의 수동 Setter (에러 해결)
    public void setTitle(String title) { this.title = title; }
    public void setLink(String link) { this.link = link; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public void setOwner(String owner) { this.owner = owner; }
    public void setRegDate(String regDate) { this.regDate = regDate; }
    public void setRawDate(LocalDateTime rawDate) { this.rawDate = rawDate; }
    public void setFeatureOption(String featureOption) { this.featureOption = featureOption; }
    public void setServerStatus(String serverStatus) { this.serverStatus = serverStatus; }
}