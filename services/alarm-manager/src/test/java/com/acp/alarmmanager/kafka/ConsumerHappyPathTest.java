package com.acp.alarmmanager.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.acp.alarmmanager.Fixtures;
import com.acp.alarmmanager.config.AlarmManagerProperties;
import com.acp.alarmmanager.service.CorrelationService;
import com.acp.alarmmanager.service.IngestService;
import com.acp.alarmmanager.service.StatusSyncService;
import com.acp.eventmodel.EventCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.support.Acknowledgment;

/** Happy-path: valid messages dispatch to the service, commit the offset, and never DLQ. */
class ConsumerHappyPathTest {

    private final EventCodec codec = new EventCodec();
    private final AlarmManagerProperties props = new AlarmManagerProperties();

    private IngestService ingest;
    private StatusSyncService statusSync;
    private CorrelationService correlation;
    private DlqRouter dlq;
    private Acknowledgment ack;

    @BeforeEach
    void setUp() {
        ingest = Mockito.mock(IngestService.class);
        statusSync = Mockito.mock(StatusSyncService.class);
        correlation = Mockito.mock(CorrelationService.class);
        dlq = Mockito.mock(DlqRouter.class);
        ack = Mockito.mock(Acknowledgment.class);
    }

    private static ConsumerRecord<String, byte[]> record(String topic, String key, String json) {
        return new ConsumerRecord<>(topic, 0, 0L, key, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void enrichedConsumerDispatchesValidAlarmAndAcks() {
        EnrichedAlarmConsumer consumer = new EnrichedAlarmConsumer(codec, ingest, dlq, props);

        consumer.onMessage(record("alarms.enriched.live", "ALM-0001",
                Fixtures.defaultAlarmEventJson()), ack);

        verify(ingest).handle(any());
        verify(dlq, never()).route(any(), any(), any(), any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void statusConsumerDispatchesValidStatusChangeAndAcks() {
        AlarmStatusChangeConsumer consumer =
                new AlarmStatusChangeConsumer(codec, statusSync, dlq, props);

        consumer.onMessage(record("alarms.status.changed", "ALM-0001",
                Fixtures.statusChangeJson("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ALM-0001",
                        "in-progress", "correlation-engine", "2026-06-13T09:05:00Z")), ack);

        verify(statusSync).apply(any());
        verify(dlq, never()).route(any(), any(), any(), any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void correlationConsumerDispatchesValidResultAndAcks() {
        CorrelationResultConsumer consumer =
                new CorrelationResultConsumer(codec, correlation, dlq, props);

        consumer.onMessage(record("correlation.results", "INC-0001",
                Fixtures.correlationJson("99999999-9999-4999-8999-999999999999", "INC-0001",
                        "ALM-0001", List.of("ALM-0002"))), ack);

        verify(correlation).applyRoleAndIncident(any());
        verify(dlq, never()).route(any(), any(), any(), any(), any());
        verify(ack).acknowledge();
    }
}
