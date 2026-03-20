package com.vectramoment.domain;

import java.time.Instant;

public record VideoMetadata(String videoId, String s3Key, String fileName, long sizeBytes, Instant uploadedAt) {}
