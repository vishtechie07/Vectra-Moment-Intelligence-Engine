package com.vectramoment.infra.constructs;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.opensearchserverless.CfnAccessPolicy;
import software.amazon.awscdk.services.opensearchserverless.CfnCollection;
import software.amazon.awscdk.services.opensearchserverless.CfnSecurityPolicy;
import software.constructs.Construct;

public class OpenSearchConstruct extends Construct {

    private static final String COLLECTION_NAME = "vectramoment";

    private final CfnCollection collection;

    public OpenSearchConstruct(final Construct scope, final String id) {
        super(scope, id);
        var accountId = Stack.of(this).getAccount();

        var encryptionPolicy = CfnSecurityPolicy.Builder.create(this, "EncryptionPolicy")
                .name("vectramoment-encryption")
                .type("encryption")
                .policy("{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/" + COLLECTION_NAME + "\"]}],\"AWSOwnedKey\":true}")
                .build();

        var networkPolicy = CfnSecurityPolicy.Builder.create(this, "NetworkPolicy")
                .name("vectramoment-network")
                .type("network")
                .policy("[{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/" + COLLECTION_NAME + "\"]}],\"AllowFromPublic\":true}]")
                .build();

        collection = CfnCollection.Builder.create(this, "Collection")
                .name(COLLECTION_NAME)
                .type("VECTORSEARCH")
                .description("VectraMoment vector search collection")
                .build();
        collection.getNode().addDependency(encryptionPolicy);
        collection.getNode().addDependency(networkPolicy);

        var accessPolicy = CfnAccessPolicy.Builder.create(this, "AccessPolicy")
                .name("vectramoment-access")
                .type("data")
                .description("Allow account access to vectramoment collection")
                .policy("[{\"Description\":\"VectraMoment collection access\",\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/" + COLLECTION_NAME + "\"],\"Permission\":[\"aoss:*\"]},{\"ResourceType\":\"index\",\"Resource\":[\"index/" + COLLECTION_NAME + "/*\"],\"Permission\":[\"aoss:*\"]}],\"Principal\":[\"arn:aws:iam::" + accountId + ":root\"]}]")
                .build();
        collection.getNode().addDependency(accessPolicy);
    }

    public CfnCollection getCollection() {
        return collection;
    }

    public String getCollectionName() {
        return COLLECTION_NAME;
    }
}
