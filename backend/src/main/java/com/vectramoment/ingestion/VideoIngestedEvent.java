package com.vectramoment.ingestion;

import com.fasterxml.jackson.annotation.JsonCreator;

public record VideoIngestedEvent(
        String videoId,
        String s3Key,
        String openAiKey,
        String localPath
) {
    public VideoIngestedEvent(String videoId, String s3Key, String openAiKey) {
        this(videoId, s3Key, openAiKey, null);
    }
    @JsonCreator
    public VideoIngestedEvent {}
}
