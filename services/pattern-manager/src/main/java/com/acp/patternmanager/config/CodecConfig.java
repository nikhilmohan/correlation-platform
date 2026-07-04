package com.acp.patternmanager.config;

import com.acp.eventmodel.EventCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the frozen {@link EventCodec} (envelope + payload validation + schemaVersion policy) as a
 * Spring bean. The codec owns its own canonical-wire {@link com.fasterxml.jackson.databind.ObjectMapper}
 * (NON_NULL) for building/validating Kafka events; Spring MVC keeps its Boot-configured mapper for
 * the HTTP surface (so {@code java.time} rendering, etc. behave as usual).
 */
@Configuration
public class CodecConfig {

    /** @return the frozen event-model codec (validates on both deserialize and serialize). */
    @Bean
    public EventCodec eventCodec() {
        return new EventCodec();
    }
}
