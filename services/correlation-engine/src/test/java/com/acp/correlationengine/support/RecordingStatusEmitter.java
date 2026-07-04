package com.acp.correlationengine.support;

import com.acp.correlationengine.correlate.AlarmStatusEmitter;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for {@link AlarmStatusEmitter} that records every fired transition, so tests can
 * assert the exact set of {@code AlarmStatusChange} events (in-progress / correlated / reverted-open)
 * without a Kafka broker.
 */
public class RecordingStatusEmitter implements AlarmStatusEmitter {

    public record Fired(String alarmId, String newStatus, long changedAtEpochMs) {
    }

    public final List<Fired> fired = new ArrayList<>();

    @Override
    public void fireInProgress(String alarmId, long changedAtEpochMs) {
        fired.add(new Fired(alarmId, "in-progress", changedAtEpochMs));
    }

    @Override
    public void fireCorrelated(String alarmId, long changedAtEpochMs) {
        fired.add(new Fired(alarmId, "correlated", changedAtEpochMs));
    }

    @Override
    public void fireRevertedOpen(String alarmId, long changedAtEpochMs) {
        fired.add(new Fired(alarmId, "reverted-open", changedAtEpochMs));
    }

    public List<String> alarmIdsWith(String status) {
        return fired.stream().filter(f -> f.newStatus().equals(status)).map(Fired::alarmId).toList();
    }

    public long countWith(String status) {
        return fired.stream().filter(f -> f.newStatus().equals(status)).count();
    }
}
