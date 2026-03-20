package com.vectramoment.domain;

public record SearchHit(String videoId, int timestampSeconds, String snippet, double score) {}
