package com.vectramoment.infra;

import com.vectramoment.infra.constructs.DynamoDbConstruct;
import com.vectramoment.infra.constructs.OpenSearchConstruct;
import com.vectramoment.infra.constructs.StorageConstruct;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public class VectraMomentStack extends Stack {

    public VectraMomentStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);
        var storage = new StorageConstruct(this, "Storage");
        var dynamo = new DynamoDbConstruct(this, "DynamoDB");
        var search = new OpenSearchConstruct(this, "OpenSearch");

        CfnOutput.Builder.create(this, "RawVideosBucket")
                .value(storage.getRawVideosBucket().getBucketName())
                .description("S3 bucket for raw video uploads")
                .exportName("VectraMoment-RawVideosBucket")
                .build();
        CfnOutput.Builder.create(this, "FramesBucket")
                .value(storage.getFramesBucket().getBucketName())
                .description("S3 bucket for extracted frames")
                .exportName("VectraMoment-FramesBucket")
                .build();
        CfnOutput.Builder.create(this, "VideoMetadataTable")
                .value(dynamo.getTable().getTableName())
                .description("DynamoDB table for video metadata")
                .exportName("VectraMoment-VideoMetadataTable")
                .build();
        CfnOutput.Builder.create(this, "OpenSearchCollectionName")
                .value(search.getCollectionName())
                .description("OpenSearch Serverless collection name; get endpoint via: aws opensearchserverless get-collection --id vectramoment")
                .exportName("VectraMoment-OpenSearchCollectionName")
                .build();
    }
}
