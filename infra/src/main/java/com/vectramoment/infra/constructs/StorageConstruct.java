package com.vectramoment.infra.constructs;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

public class StorageConstruct extends Construct {

    private final Bucket rawVideosBucket;
    private final Bucket framesBucket;

    public StorageConstruct(final Construct scope, final String id) {
        super(scope, id);
        var stack = Stack.of(this);
        rawVideosBucket = Bucket.Builder.create(this, "RawVideos")
                .bucketName(stack.getAccount() + "-vectramoment-raw-videos")
                .build();
        framesBucket = Bucket.Builder.create(this, "Frames")
                .bucketName(stack.getAccount() + "-vectramoment-frames")
                .build();
    }

    public Bucket getRawVideosBucket() {
        return rawVideosBucket;
    }

    public Bucket getFramesBucket() {
        return framesBucket;
    }
}
