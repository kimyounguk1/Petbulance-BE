package com.example.Petbulance_BE.global.external.claude;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ImageEncoderTest {

    @Test
    @DisplayName("나눠 읽기 변환 결과는 한 번에 변환한 결과와 같다 (청크 경계, 패딩 포함)")
    void streamingEqualsDirect() throws Exception {
        for (int size : new int[]{0, 1, 2, 3, 8 * 1024, 8 * 1024 + 1, 100_000}) {
            byte[] data = new byte[size];
            new Random(size).nextBytes(data);
            MockMultipartFile file = new MockMultipartFile("image", "a.jpg", "image/jpeg", data);

            assertThat(ImageEncoder.encodeBase64Streaming(file))
                    .as("size=%d", size)
                    .isEqualTo(ImageEncoder.encodeBase64Direct(file));
        }
    }
}
