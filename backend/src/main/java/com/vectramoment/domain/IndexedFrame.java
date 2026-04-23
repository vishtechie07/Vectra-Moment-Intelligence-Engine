package com.vectramoment.domain;

/** Frame fields stored in OpenSearch (no embedding vector). */
public record IndexedFrame(String videoId, int timestampSeconds, String description) {}
