package com.vectramoment.web.dto;

public record SearchHitDto(String videoId, int timestampSeconds, String snippet, double score) {}
