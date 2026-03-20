package com.vectramoment.processing;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FrameEntry(int timestampSeconds, String s3Key, @JsonProperty(required = false) String localPath) {
    public FrameEntry(int timestampSeconds, String s3Key) {
        this(timestampSeconds, s3Key, null);
    }
}
