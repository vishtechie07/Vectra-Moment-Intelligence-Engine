package com.vectramoment.domain;

/**
 * A frame's stored metadata in OpenSearch (no embedding). Used for AI-based search comparison.
 */
public record IndexedFrame(String videoId, int timestampSeconds, String description) {}
