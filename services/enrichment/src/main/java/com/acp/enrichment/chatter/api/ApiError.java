package com.acp.enrichment.chatter.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The structured error body (OpenAPI 3.1 {@code Error}).
 *
 * @param code machine-readable code (e.g. {@code unknown_source}, {@code malformed_entry},
 *     {@code duplicate_entry})
 * @param message human-readable detail
 */
@Schema(name = "Error")
public record ApiError(String code, String message) {
}
