package com.example.Petbulance_BE.global.external.claude;

/** Claude 요청에 넣을 이미지 (base64). mediaType 은 image/jpeg, image/png, image/gif, image/webp */
public record ClaudeImage(String mediaType, String base64Data) {
}
