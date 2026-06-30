package com.acp.enrichment.chatter.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The list response for a source's effective chatter entries (OpenAPI 3.1
 * {@code ChatterListResponse}).
 *
 * @param source the source whose chatter list this is
 * @param entries the effective entries (base YAML + overlay adds - overlay removes)
 * @param total the entry count
 */
@Schema(name = "ChatterListResponse")
public record ChatterListResponse(String source, List<ChatterEntryDto> entries, int total) {

    public static ChatterListResponse of(String source, List<ChatterEntryDto> entries) {
        return new ChatterListResponse(source, entries, entries.size());
    }
}
