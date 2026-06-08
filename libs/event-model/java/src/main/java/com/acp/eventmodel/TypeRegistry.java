package com.acp.eventmodel;

import com.acp.eventmodel.generated.AlarmEvent;
import com.acp.eventmodel.generated.CodebookGeneratedEvent;
import com.acp.eventmodel.generated.CorrelationResultEvent;
import com.acp.eventmodel.generated.PatternApprovedEvent;
import com.acp.eventmodel.generated.PatternDiscoveredEvent;
import com.acp.eventmodel.generated.PatternMinedEvent;
import com.acp.eventmodel.generated.TopologyChangedEvent;
import com.acp.eventmodel.generated.TrailsBuiltEvent;
import com.acp.eventmodel.generated.TransactionEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Discriminator registry: {@code type} string &rarr; payload class, 1:1 (criterion 5).
 *
 * <p>A thin, schema-agnostic helper. It references the generated payload <em>classes</em> (whose
 * names equal the wire {@code type} strings) but no field lists, so it does not break the
 * single-source-of-truth guarantee. Exact Java counterpart of the Python {@code registry.py}.
 */
public final class TypeRegistry {

    /** Maps each canonical {@code type} discriminator string to its generated payload class. */
    private static final Map<String, Class<?>> REGISTRY = new LinkedHashMap<>();

    static {
        REGISTRY.put("AlarmEvent", AlarmEvent.class);
        REGISTRY.put("TopologyChangedEvent", TopologyChangedEvent.class);
        REGISTRY.put("TrailsBuiltEvent", TrailsBuiltEvent.class);
        REGISTRY.put("CodebookGeneratedEvent", CodebookGeneratedEvent.class);
        REGISTRY.put("TransactionEvent", TransactionEvent.class);
        REGISTRY.put("PatternMinedEvent", PatternMinedEvent.class);
        REGISTRY.put("PatternDiscoveredEvent", PatternDiscoveredEvent.class);
        REGISTRY.put("PatternApprovedEvent", PatternApprovedEvent.class);
        REGISTRY.put("CorrelationResultEvent", CorrelationResultEvent.class);
    }

    private TypeRegistry() {
    }

    /** @return the set of the nine recognised {@code type} discriminator strings. */
    public static Set<String> knownTypes() {
        return REGISTRY.keySet();
    }

    /**
     * Resolve the payload class for {@code eventType}.
     *
     * @param eventType the envelope's {@code type} discriminator
     * @return the generated payload class
     * @throws UnknownEventTypeException if {@code eventType} is not one of the nine registered
     *     discriminator strings
     */
    public static Class<?> resolve(String eventType) {
        Class<?> cls = REGISTRY.get(eventType);
        if (cls == null) {
            throw new UnknownEventTypeException(
                    "unknown event type '" + eventType + "': expected one of " + REGISTRY.keySet());
        }
        return cls;
    }
}
