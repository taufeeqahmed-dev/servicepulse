package dev.taufeeqahmed.servicepulse.alerting;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import dev.taufeeqahmed.servicepulse.incidents.IncidentTransition;
import dev.taufeeqahmed.servicepulse.observability.IncidentMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WebhookNotifierTest {

    private final HttpClient client = mock(HttpClient.class);
    private final IncidentMetrics metrics = mock(IncidentMetrics.class);
    private final WebhookNotifier notifier = new WebhookNotifier(client, JsonMapper.builder().build(), metrics,
            Duration.ofSeconds(3));
    private final IncidentTransition event = new IncidentTransition(IncidentTransition.Event.INCIDENT_OPENED,
            1, 2, "API", "http://localhost/hook", Instant.parse("2026-10-03T14:03:00Z"), null, 3);

    @Test
    void connectionFailureIsIsolatedAndNotRetried() throws Exception {
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenThrow(new IOException("Connection refused"));
        assertThatCode(() -> notifier.notifyTransition(event)).doesNotThrowAnyException();
        verify(client, times(1)).send(any(HttpRequest.class), any());
        verify(metrics).recordWebhookDelivery(event.event(), false);
    }

    @Test
    void malformedStoredWebhookDoesNotEscapeOrStartANetworkRequest() {
        var invalid = new IncidentTransition(event.event(), 1, 2, "API", "not a URL",
                event.startedAt(), null, 3);
        assertThatCode(() -> notifier.notifyTransition(invalid)).doesNotThrowAnyException();
        verifyNoInteractions(client);
        verify(metrics).recordWebhookDelivery(event.event(), false);
    }

    @Test
    void deliveryInterruptionPreservesTheInterruptFlag() throws Exception {
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenThrow(new InterruptedException("Cancelled"));
        try {
            assertThatCode(() -> notifier.notifyTransition(event)).doesNotThrowAnyException();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(metrics).recordWebhookDelivery(event.event(), false);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void alreadyInterruptedThreadDoesNotStartANetworkRequest() {
        try {
            Thread.currentThread().interrupt();
            notifier.notifyTransition(event);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(client);
            verify(metrics).recordWebhookDelivery(event.event(), false);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 302, 503})
    @SuppressWarnings("unchecked")
    void boundsRequestsClosesResponseWithoutReadingAndClassifiesDelivery(int httpStatus) throws Exception {
        var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
        var body = mock(InputStream.class);
        when(response.statusCode()).thenReturn(httpStatus);
        when(response.body()).thenReturn(body);
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(response);
        notifier.notifyTransition(event);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any());
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(3));
        assertThat(request.getValue().headers().firstValue("Content-Type")).contains("application/json");
        verify(body).close();
        verifyNoMoreInteractions(body);
        verify(metrics).recordWebhookDelivery(event.event(), httpStatus >= 200 && httpStatus < 300);
    }
}
