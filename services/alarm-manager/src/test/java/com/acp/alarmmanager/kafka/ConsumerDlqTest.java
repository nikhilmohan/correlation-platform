package com.acp.alarmmanager.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.acp.alarmmanager.Fixtures;
import com.acp.alarmmanager.config.AlarmManagerProperties;
import com.acp.alarmmanager.service.CorrelationService;
import com.acp.alarmmanager.service.IngestService;
import com.acp.alarmmanager.service.StatusSyncService;
import com.acp.eventmodel.EventCodec;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.support.Acknowledgment;

/**
 * AC12 — schema-invalid AlarmEvent (missing alarmId) routes to alarms.enriched.live.dlq, no
 * persist, no republish. AC13 — unknown major schemaVersion routes to DLQ, no state change.
 * AC15 — malformed managedObjectId routes to enriched DLQ, not persisted. AC20 — schema-invalid /
 * bad-newStatus AlarmStatusChange routes to alarms.status.changed.dlq, store unmodified,
 * processing of a subsequent valid message continues.
 */
class ConsumerDlqTest {

    private final EventCodec codec = new EventCodec();
    private final AlarmManagerProperties props = new AlarmManagerProperties();

    private IngestService ingest;
    private StatusSyncService statusSync;
    private CorrelationService correlation;
    private DlqRouter dlq;
    private Acknowledgment ack;

    private EnrichedAlarmConsumer enrichedConsumer;
    private AlarmStatusChangeConsumer statusConsumer;
    private CorrelationResultConsumer correlationConsumer;

    @BeforeEach
    void setUp() {
        ingest = Mockito.mock(IngestService.class);
        statusSync = Mockito.mock(StatusSyncService.class);
        correlation = Mockito.mock(CorrelationService.class);
        dlq = Mockito.mock(DlqRouter.class);
        ack = Mockito.mock(Acknowledgment.class);
        enrichedConsumer = new EnrichedAlarmConsumer(codec, ingest, dlq, props);
        statusConsumer = new AlarmStatusChangeConsumer(codec, statusSync, dlq, props);
        correlationConsumer = new CorrelationResultConsumer(codec, correlation, dlq, props);
    }

    private static ConsumerRecord<String, byte[]> record(String topic, String key, String json) {
        return new ConsumerRecord<>(topic, 0, 0L, key,
                json == null ? null : json.getBytes(StandardCharsets.UTF_8));
    }

    // --- AC12 ---
    @Test
    void schemaInvalidAlarmRoutedToDlqNoPersistNoRepublish() {
        String badJson = """
                {
                  "eventId": "11111111-1111-4111-8111-111111111111",
                  "type": "AlarmEvent",
                  "schemaVersion": 1,
                  "occurredAt": "2026-06-13T09:00:00Z",
                  "source": "enrichment",
                  "traceId": "trace-bad",
                  "payload": {
                    "managedObjectId": "Port:ne1-1-1",
                    "eventType": "communicationsAlarm",
                    "probableCause": "lossOfSignal",
                    "alarmType": "PortDown",
                    "perceivedSeverity": "critical",
                    "raisedAt": "2026-06-13T09:00:00Z",
                    "state": "raised",
                    "trailIds": []
                  }
                }
                """;
        enrichedConsumer.onMessage(record("alarms.enriched.live", "k", badJson), ack);

        verify(ingest, never()).handle(any());
        verify(dlq).route(eq("alarms.enriched.live.dlq"), eq("alarms.enriched.live"), eq("k"),
                any(), any());
        verify(ack).acknowledge();
    }

    // --- AC13 ---
    @Test
    void unknownMajorSchemaVersionRejectedToDlq() {
        String v2 = Fixtures.defaultAlarmEventJson().replace("\"schemaVersion\": 1",
                "\"schemaVersion\": 2");
        enrichedConsumer.onMessage(record("alarms.enriched.live", "k", v2), ack);

        verify(ingest, never()).handle(any());
        verify(dlq).route(eq("alarms.enriched.live.dlq"), any(), any(), any(), any());
    }

    // --- AC15 ---
    @Test
    void malformedManagedObjectIdRoutedToDlqAndNotPersisted() {
        String bad = Fixtures.defaultAlarmEventJson().replace("Port:ne1-1-1", "not-a-valid-moid");
        enrichedConsumer.onMessage(record("alarms.enriched.live", "k", bad), ack);

        verify(ingest, never()).handle(any());
        verify(dlq).route(eq("alarms.enriched.live.dlq"), any(), any(), any(), any());
    }

    // --- AC20 ---
    @Test
    void invalidStatusChangeRoutedToDlqStoreUnmodifiedProcessingContinues() {
        // Missing alarmId.
        String missingAlarmId = """
                {
                  "eventId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                  "type": "AlarmStatusChange",
                  "schemaVersion": 1,
                  "occurredAt": "2026-06-13T09:05:00Z",
                  "source": "correlation-engine",
                  "traceId": "t",
                  "payload": { "newStatus": "correlated", "source": "correlation-engine",
                    "changedAt": "2026-06-13T09:05:00Z" }
                }
                """;
        statusConsumer.onMessage(record("alarms.status.changed", "k", missingAlarmId), ack);
        verify(statusSync, never()).apply(any());
        verify(dlq).route(eq("alarms.status.changed.dlq"), eq("alarms.status.changed"), any(),
                any(), any());

        // Unrecognised newStatus value.
        String badStatus = Fixtures.statusChangeJson("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "ALM-0001", "not-a-status", "correlation-engine", "2026-06-13T09:05:00Z");
        statusConsumer.onMessage(record("alarms.status.changed", "k", badStatus), ack);
        verify(statusSync, never()).apply(any());

        // A subsequent VALID status message is still processed (processing continues).
        String valid = Fixtures.statusChangeJson("cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "ALM-0001", "in-progress", "correlation-engine", "2026-06-13T09:05:00Z");
        statusConsumer.onMessage(record("alarms.status.changed", "k", valid), ack);
        verify(statusSync).apply(any());
    }

    @Test
    void invalidCorrelationRoutedToCorrelationDlq() {
        String bad = "{ not json";
        correlationConsumer.onMessage(record("correlation.results", "k", bad), ack);

        verify(correlation, never()).applyRoleAndIncident(any());
        verify(dlq).route(eq("correlation.results.dlq"), eq("correlation.results"), any(), any(),
                any());
    }

    @Test
    void wrongEnvelopeTypeOnStatusTopicRoutedToDlq() {
        // A valid AlarmEvent envelope delivered on the status topic — wrong type -> DLQ.
        statusConsumer.onMessage(
                record("alarms.status.changed", "k", Fixtures.defaultAlarmEventJson()), ack);

        verify(statusSync, never()).apply(any());
        verify(dlq).route(eq("alarms.status.changed.dlq"), any(), any(), any(), any());
    }
}
