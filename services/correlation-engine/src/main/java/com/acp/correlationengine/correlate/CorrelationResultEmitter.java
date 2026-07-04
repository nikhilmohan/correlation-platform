package com.acp.correlationengine.correlate;

import com.acp.correlationengine.model.Incident;

/**
 * Port for emitting one {@code CorrelationResultEvent} on {@code correlation.results} per committed
 * incident (persist-then-emit). Implemented over the event-model binding + Kafka in production;
 * captured in unit tests. The engine core depends on this port, not on Kafka.
 */
public interface CorrelationResultEmitter {

    /** Emit a {@code CorrelationResultEvent} for the committed incident. */
    void emit(Incident incident);
}
