package dev.taufeeqahmed.servicepulse.alerting;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import dev.taufeeqahmed.servicepulse.incidents.IncidentTransition;
import dev.taufeeqahmed.servicepulse.observability.IncidentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class WebhookNotifier {

    private static final Logger logger = LoggerFactory.getLogger(WebhookNotifier.class);
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final IncidentMetrics metrics;
    private final Duration requestTimeout;

    public WebhookNotifier(HttpClient httpClient, ObjectMapper mapper, IncidentMetrics metrics,
            @Value("${servicepulse.webhook.request-timeout}") Duration requestTimeout) {
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("Webhook request timeout must be positive.");
        }
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.metrics = metrics;
        this.requestTimeout = requestTimeout;
    }

    // Called by the result coordinator only after its transaction has committed and released resources.
    public void notifyTransition(IncidentTransition transition) {
        if (transition.webhookUrl() == null) {
            return;
        }
        boolean success = false;
        try {
            if (Thread.currentThread().isInterrupted()) {
                logger.warn("Webhook skipped on interrupted thread: incidentId={}, event={}",
                        transition.incidentId(), transition.event());
                return;
            }
            var request = HttpRequest.newBuilder(URI.create(transition.webhookUrl()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(WebhookPayload.from(transition))))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                // Do not read arbitrary response bodies or wait for them to finish.
                success = response.statusCode() >= 200 && response.statusCode() < 300;
            }
            if (!success) {
                logger.warn("Webhook rejected: incidentId={}, event={}, httpStatus={}",
                        transition.incidentId(), transition.event(), response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Webhook interrupted: incidentId={}, event={}", transition.incidentId(), transition.event());
        } catch (IOException | RuntimeException exception) {
            success = false;
            // URLs and exception messages can contain webhook credentials. Log only safe identifiers.
            logger.warn("Webhook delivery failed: incidentId={}, event={}", transition.incidentId(), transition.event());
        } finally {
            metrics.recordWebhookDelivery(transition.event(), success);
        }
    }
}
