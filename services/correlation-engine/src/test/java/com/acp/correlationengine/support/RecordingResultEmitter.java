package com.acp.correlationengine.support;

import com.acp.correlationengine.correlate.CorrelationResultEmitter;
import com.acp.correlationengine.model.Incident;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Test double for {@link CorrelationResultEmitter} that records the emitted incidents. */
public class RecordingResultEmitter implements CorrelationResultEmitter {

    public final List<Incident> emitted = new ArrayList<>();

    @Override
    public void emit(Incident incident) {
        emitted.add(incident);
    }

    public Optional<Incident> last() {
        return emitted.isEmpty() ? Optional.empty() : Optional.of(emitted.get(emitted.size() - 1));
    }
}
