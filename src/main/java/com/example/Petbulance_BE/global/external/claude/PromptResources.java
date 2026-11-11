package com.example.Petbulance_BE.global.external.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 프롬프트와 출력 스키마는 코드가 아니라 resources/ai 아래 파일로 관리한다 (변경 이력이 diff 로 보이게) */
public final class PromptResources {

    private PromptResources() {
    }

    public static String text(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("리소스를 읽지 못했습니다: " + path, e);
        }
    }

    public static JsonNode json(ObjectMapper objectMapper, String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("리소스를 읽지 못했습니다: " + path, e);
        }
    }
}
