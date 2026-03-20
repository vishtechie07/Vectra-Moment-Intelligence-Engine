package com.vectramoment.processing;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.List;

public record FrameReadyEvent(
        String videoId,
        List<FrameEntry> frames,
        String openAiKey
) {
    @JsonCreator
    public FrameReadyEvent {}
}
