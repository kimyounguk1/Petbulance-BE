package com.example.Petbulance_BE.global.external.claude;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 이미지 → base64 변환.
 *
 * <p>{@code file.getBytes()} 로 원본 전체를 메모리에 올린 뒤 변환하면 원본(1배)과 결과(약 1.33배)가 동시에 힙에 남는다.
 * 고화질 이미지가 몰리면 이 큰 배열들이 Old 영역에 쌓여 OOM 이 났기 때문에,
 * 원본은 8KB 씩 나눠 읽으면서 바로 변환해 원본 전체 배열을 만들지 않는다.
 */
public final class ImageEncoder {

    private static final int CHUNK_SIZE = 8 * 1024;

    private ImageEncoder() {
    }

    public static String encodeBase64Streaming(MultipartFile file) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (InputStream in = file.getInputStream();
             OutputStream base64 = Base64.getEncoder().wrap(encoded)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                base64.write(buffer, 0, read);
            }
        } // base64 스트림을 닫아야 마지막 패딩이 기록된다
        return encoded.toString(StandardCharsets.US_ASCII);
    }

    /** 비교용: 원본 전체를 한 번에 올려 변환 (부하 테스트 목 엔드포인트에서만 사용) */
    public static String encodeBase64Direct(MultipartFile file) throws IOException {
        return Base64.getEncoder().encodeToString(file.getBytes());
    }
}
