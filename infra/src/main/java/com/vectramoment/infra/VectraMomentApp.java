package com.vectramoment.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class VectraMomentApp {

    public static void main(final String[] args) {
        var app = new App();
        var env = Environment.builder()
                .region("ap-southeast-2")
                .build();
        new VectraMomentStack(app, "VectraMomentStack", StackProps.builder()
                .env(env)
                .build());
        app.synth();
    }
}
