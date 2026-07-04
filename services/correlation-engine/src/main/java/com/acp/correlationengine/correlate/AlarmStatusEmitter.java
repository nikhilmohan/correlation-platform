package com.acp.correlationengine.correlate;

/**
 * Port for firing {@code AlarmStatusChange} events on {@code alarms.status.changed}
 * ({@code source = correlation-engine}) on the three lifecycle transitions:
 * {@code in-progress} (admission), {@code correlated} (full match), {@code reverted-open}
 * (session expiry). No transition may be silently omitted. Implemented over the event-model
 * binding + Kafka in production; captured in unit tests.
 */
public interface AlarmStatusEmitter {

    String SOURCE = "correlation-engine";

    /** Fire {@code in-progress} for an alarm admitted to an active instance (AC6). */
    void fireInProgress(String alarmId, long changedAtEpochMs);

    /** Fire {@code correlated} for a root-cause or child alarm of a fully-matched instance (AC4). */
    void fireCorrelated(String alarmId, long changedAtEpochMs);

    /** Fire {@code reverted-open} for an accumulated alarm of an expired instance (AC5). */
    void fireRevertedOpen(String alarmId, long changedAtEpochMs);
}
