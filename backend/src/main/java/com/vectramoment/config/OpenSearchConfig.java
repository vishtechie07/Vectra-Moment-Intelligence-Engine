package com.vectramoment.config;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.acm19.aws.interceptor.http.AwsRequestSigningApacheV5Interceptor;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.regions.Region;
import java.util.Base64;

@Configuration
public class OpenSearchConfig {

    @Value("${vectramoment.opensearch.endpoint:http://localhost:9200}")
    private String endpoint;
    @Value("${vectramoment.opensearch.username:}")
    private String username;
    @Value("${vectramoment.opensearch.password:}")
    private String password;

    @Value("${vectramoment.aws.region:ap-southeast-2}")
    private String awsRegion;

    @Bean
    public OpenSearchClient opensearchClient() {
        try {
            var builder = ApacheHttpClient5TransportBuilder.builder(HttpHost.create(endpoint));
            boolean hasBasicAuth = username != null && !username.isBlank() && password != null && !password.isBlank();
            if (hasBasicAuth) {
                String creds = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                builder.setDefaultHeaders(new Header[]{ new BasicHeader("Authorization", "Basic " + creds) });
            } else if (endpoint != null && endpoint.startsWith("https://")) {
                // OpenSearch Serverless uses SigV4 (IAM) auth; apply request signing when Basic auth is not configured.
                // Per AWS docs/samples the service name for OpenSearch Serverless is "aoss".
                var interceptor = new AwsRequestSigningApacheV5Interceptor(
                        "aoss",
                        AwsV4HttpSigner.create(),
                        DefaultCredentialsProvider.builder().build(),
                        Region.of(awsRegion)
                );
                builder.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                        .addExecInterceptorLast("aws-signing-interceptor", interceptor));
            }
            return new OpenSearchClient(builder.build());
        } catch (Exception e) {
            throw new RuntimeException("OpenSearch client init failed", e);
        }
    }
}
