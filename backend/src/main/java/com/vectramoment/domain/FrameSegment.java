package com.vectramoment.domain;

public record FrameSegment(int timestampSeconds, String s3Key, String description, float[] embedding) {}
