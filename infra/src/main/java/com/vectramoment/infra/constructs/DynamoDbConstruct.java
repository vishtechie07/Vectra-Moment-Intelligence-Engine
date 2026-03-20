package com.vectramoment.infra.constructs;

import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.constructs.Construct;

public class DynamoDbConstruct extends Construct {

    private final Table table;

    public DynamoDbConstruct(final Construct scope, final String id) {
        super(scope, id);
        table = Table.Builder.create(this, "VideoMetadata")
                .tableName("vectramoment-video-metadata")
                .partitionKey(Attribute.builder().name("videoId").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();
    }

    public Table getTable() {
        return table;
    }
}
