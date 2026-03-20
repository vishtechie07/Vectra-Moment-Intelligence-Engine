package com.vectramoment.integration;

import com.vectramoment.ingestion.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(com.vectramoment.web.VideoUploadController.class)
@TestPropertySource(properties = "vectramoment.aws.s3.raw-bucket=test-bucket")
class VideoUploadIT {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IngestionService ingestionService;

    @MockitoBean
    private S3Client s3Client;

    @Test
    void uploadRejectsEmptyFile() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "test.mp4", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[0]);
        mvc.perform(multipart("/api/videos/upload").file(empty))
                .andExpect(status().isBadRequest());
    }
}
