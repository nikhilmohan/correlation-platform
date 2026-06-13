package com.acp.knowledge.validation;

/**
 * One structured validation violation.
 *
 * @param field the offending field (JSON path / param key / entry)
 * @param rule the validation rule violated (e.g. {@code edge-type-in-vocabulary},
 *     {@code token-format}, {@code param-bounds})
 * @param message a clear human-readable message naming the offending value
 */
public record Violation(String field, String rule, String message) {
}
