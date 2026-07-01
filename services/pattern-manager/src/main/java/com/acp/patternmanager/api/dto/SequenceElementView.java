package com.acp.patternmanager.api.dto;

/**
 * A sequence element on a {@code PatternView}.
 *
 * @param alarmType the alarm-type vocabulary token
 * @param optional whether the operator marked this position optional (edit placeholder)
 */
public record SequenceElementView(String alarmType, boolean optional) {
}
