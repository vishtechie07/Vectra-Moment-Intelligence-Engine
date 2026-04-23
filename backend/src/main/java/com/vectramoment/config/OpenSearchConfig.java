package com.vectramoment.config;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

@Configuration
public class OpenSearchConfig {

    @Value("${vectramoment.opensearch.endpoint:http://localhost:9200}")
    private String endpoint;
    @Value("${vectramoment.opensearch.username:}")
    private String username;
    @Value("${vectramoment.opensearch.password:}")
    private String password;

    @Bean
    public OpenSearchClient opensearchClient() {
        try {
            var builder = ApacheHttpClient5TransportBuilder.builder(HttpHost.create(endpoint));
            boolean hasBasicAuth = username != null && !username.isBlank() && password != null && !password.isBlank();
            if (hasBasicAuth) {
                String creds = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                builder.setDefaultHeaders(new Header[]{ new BasicHeader("Authorization", "Basic " + creds) });
            }
            return new OpenSearchClient(builder.build());
        } catch (Exception e) {
            throw new RuntimeException("OpenSearch client init failed", e);
        }
    }
}
