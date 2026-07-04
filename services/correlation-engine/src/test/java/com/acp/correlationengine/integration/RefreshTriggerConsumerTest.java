package com.acp.correlationengine.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.acp.correlationengine.codebook.CodebookRefreshService;
import com.acp.correlationengine.config.CorrelationEngineProperties;
import com.acp.correlationengine.generalize.CompatibilityIndexService;
import com.acp.correlationengine.pattern.PatternRefreshService;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.CodebookGeneratedEvent;
import com.acp.eventmodel.generated.PatternApprovedEvent;
import com.acp.eventmodel.generated.SessionWindow;
import com.acp.eventmodel.generated.Timing;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two trigger consumers ({@code codebook.generated}, {@code patterns.approved}). Proves each:
 * decodes the frozen envelope and drives the corresponding refresh, dedupes on {@code eventId} so a
 * redelivered event is a no-op (AC20/AC27, idempotency), and routes a poison/off-type message to the
 * DLQ rather than dropping it (AC19).
 */
class RefreshTriggerConsumerTest {

    private final EventCodec codec = new EventCodec();
    private final CorrelationEngineProperties props = new CorrelationEngineProperties(
            "mock", "u", "u", "u", "core-ip", 1000, 1000, "off", "u", "mock", 2, null);

    // ---- codebook.generated ------------------------------------------------

    @Test
    void codebook_validEvent_drivesRefreshWithCodebookAndSnapshot() {
        CodebookRefreshService refresh = mock(CodebookRefreshService.class);
        DlqProducer dlq = mock(DlqProducer.class);
        CodebookConsumer consumer = new CodebookConsumer(
                refresh, new InMemoryProcessedEventStore(), codec, dlq, props);

        consumer.onMessage(codebookWire("evt-cb-1", "CB-9", "SNAP-1"));

        verify(refresh).onCodebookGenerated("CB-9", "SNAP-1");
        verifyNoInteractions(dlq);
    }

    @Test
    void codebook_redeliveredEvent_isIdempotentNoOp() {
        CodebookRefreshService refresh = mock(CodebookRefreshService.class);
        DlqProducer dlq = mock(DlqProducer.class);
        CodebookConsumer consumer = new CodebookConsumer(
                refresh, new InMemoryProcessedEventStore(), codec, dlq, props);

        String wire = codebookWire("evt-cb-dup", "CB-1", "SNAP-1");
        consumer.onMessage(wire);
        consumer.onMessage(wire); // redelivered — same eventId

        verify(refresh, times(1)).onCodebookGenerated("CB-1", "SNAP-1");
    }

    @Test
    void codebook_poisonMessage_routedToDlq_refreshUntouched() {
        CodebookRefreshService refresh = mock(CodebookRefreshService.class);
        DlqProducer dlq = mock(DlqProducer.class);
        CodebookConsumer consumer = new CodebookConsumer(
                refresh, new InMemoryProcessedEventStore(), codec, dlq, props);

        consumer.onMessage("{ not valid json");

        verify(dlq).route(eq("codebook.generated"), any(), eq("{ not valid json"), any());
        verify(refresh, never()).onCodebookGenerated(any(), any());
    }

    // ---- patterns.approved -------------------------------------------------

    @Test
    void patterns_validEvent_triggersRefetchOfApprovedSet() {
        PatternRefreshService refresh = mock(PatternRefreshService.class);
        CompatibilityIndexService index = mock(CompatibilityIndexService.class);
        DlqProducer dlq = mock(DlqProducer.class);
        PatternApprovedConsumer consumer = new PatternApprovedConsumer(
                refresh, index, new InMemoryProcessedEventStore(), codec, dlq, props);

        consumer.onMessage(patternWire("evt-pat-1", "PAT-1"));

        verify(refresh).refreshOnApproval();
        verify(index).rebuildForApprovedSet(); // AC38: index updated before ack
        verifyNoInteractions(dlq);
    }

    @Test
    void patterns_redeliveredEvent_isIdempotentNoOp() {
        PatternRefreshService refresh = mock(PatternRefreshService.class);
        CompatibilityIndexService index = mock(CompatibilityIndexService.class);
        DlqProducer dlq = mock(DlqProducer.class);
        PatternApprovedConsumer consumer = new PatternApprovedConsumer(
                refresh, index, new InMemoryProcessedEventStore(), codec, dlq, props);

        String wire = patternWire("evt-pat-dup", "PAT-2");
        consumer.onMessage(wire);
        consumer.onMessage(wire);

        verify(refresh, times(1)).refreshOnApproval();
        verify(index, times(1)).rebuildForApprovedSet();
    }

    @Test
    void patterns_poisonMessage_routedToDlq_refreshUntouched() {
        PatternRefreshService refresh = mock(PatternRefreshService.class);
        CompatibilityIndexService index = mock(CompatibilityIndexService.class);
        DlqProducer dlq = mock(DlqProducer.class);
        PatternApprovedConsumer consumer = new PatternApprovedConsumer(
                refresh, index, new InMemoryProcessedEventStore(), codec, dlq, props);

        consumer.onMessage("}}garbage");

        verify(dlq).route(eq("patterns.approved"), any(), eq("}}garbage"), any());
        verify(refresh, never()).refreshOnApproval();
    }

    // ---- helpers -----------------------------------------------------------

    private String codebookWire(String eventId, String codebookId, String snapshotId) {
        CodebookGeneratedEvent payload = new CodebookGeneratedEvent()
                .withSnapshotId(snapshotId)
                .withDomain("core-ip")
                .withScenarioCount(12)
                .withCodebookId(codebookId);
        TypedEnvelope<CodebookGeneratedEvent> envelope = new TypedEnvelope<>(
                eventId, "CodebookGeneratedEvent", 1, "2026-06-11T12:00:00Z",
                "codebook-generator", "trace-cb", payload);
        return codec.serialize(envelope);
    }

    private String patternWire(String eventId, String patternId) {
        PatternApprovedEvent payload = new PatternApprovedEvent()
                .withPatternId(patternId)
                .withSequence(List.of("lossOfSignal", "linkDown"))
                .withRootCauseAlarmType("lossOfSignal")
                .withSupport(0.42)
                .withConfidence(0.87)
                .withLift(3.1)
                .withTiming(new Timing()
                        .withAdditionalProperty("timeframeMs", 9000)
                        .withAdditionalProperty("medianInterArrivalMs", 4500))
                .withSessionWindow(new SessionWindow()
                        .withWindowMs(60000)
                        .withType(SessionWindow.Type.GAP_BASED))
                .withCodebookMatchId("SCN-0007")
                .withLifecycle("approved");
        TypedEnvelope<PatternApprovedEvent> envelope = new TypedEnvelope<>(
                eventId, "PatternApprovedEvent", 1, "2026-06-11T12:00:00Z",
                "pattern-manager", "trace-pat", payload);
        return codec.serialize(envelope);
    }
}
