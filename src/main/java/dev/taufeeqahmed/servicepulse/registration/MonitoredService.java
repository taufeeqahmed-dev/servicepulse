package dev.taufeeqahmed.servicepulse.registration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "monitored_services")
public class MonitoredService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false)
    @ColumnDefault("3")
    private int failureThreshold = 3;

    @Column(nullable = false)
    @ColumnDefault("0")
    private long consecutiveFailures;

    @Column(length = 2048)
    private String webhookUrl;

    protected MonitoredService() {
    }

    public MonitoredService(String name, String url) {
        this(name, url, 3, null);
    }

    public MonitoredService(String name, String url, int failureThreshold, String webhookUrl) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("Failure threshold must be greater than zero.");
        }
        this.name = name;
        this.url = url;
        this.failureThreshold = failureThreshold;
        this.webhookUrl = webhookUrl;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public long getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void recordFailure() {
        consecutiveFailures++;
    }

    public void resetFailures() {
        consecutiveFailures = 0;
    }
}
