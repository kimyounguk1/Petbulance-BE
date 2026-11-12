package com.example.Petbulance_BE.domain.review.service;

import com.example.Petbulance_BE.domain.ai.config.AiProperties;
import com.example.Petbulance_BE.domain.review.dto.ReceiptAiDto.ExtractedData;
import com.example.Petbulance_BE.domain.review.dto.ReceiptAiDto.ReceiptAiOutput;
import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;
import com.example.Petbulance_BE.global.external.ExternalFailures;
import com.example.Petbulance_BE.global.external.claude.AiOutputException;
import com.example.Petbulance_BE.global.external.claude.ClaudeClient;
import com.example.Petbulance_BE.global.external.claude.ClaudeImage;
import com.example.Petbulance_BE.global.external.claude.ImageEncoder;
import com.example.Petbulance_BE.global.external.claude.ClaudeResult;
import com.example.Petbulance_BE.global.external.claude.PromptResources;
import com.example.Petbulance_BE.global.external.exception.UpstreamRejectedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 영수증 사진 → 매장, 항목, 금액, 주소, 결제 시간 (Claude, 한 번에 받는 호출).
 *
 * <p>진단과 달리 스트리밍하지 않는다: 주소가 다 와야 다음 단계(지오코딩)를 할 수 있어서 먼저 받아도 쓸 곳이 없다.
 * 재시도/서킷/동시 호출 제한은 {@link ClaudeClient#createForReceipt} 의 어노테이션이 담당한다.
 */
@Slf4j
@Component
public class ReceiptAiExtractor {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private final ClaudeClient claudeClient;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final String systemPrompt;
    private final JsonNode outputSchema;

    public ReceiptAiExtractor(ClaudeClient claudeClient, ObjectMapper objectMapper, AiProperties aiProperties) {
        this.claudeClient = claudeClient;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        this.systemPrompt = PromptResources.text("ai/receipt-system-prompt.txt");
        this.outputSchema = PromptResources.json(objectMapper, "ai/receipt-schema.json");
    }

    public ExtractedData extract(MultipartFile image) {
        if (!aiProperties.isEnabled()) {
            log.warn("AI 기능 비활성화 상태(킬스위치)");
            throw new CustomException(ErrorCode.AI_UNAVAILABLE);
        }
        ClaudeImage claudeImage = encode(image);

        try {
            ClaudeResult result = claudeClient.createForReceipt(
                    claudeClient.buildRequest(systemPrompt, List.of(claudeImage), "이 영수증에서 값을 추출하세요.", outputSchema));
            ReceiptAiOutput output = objectMapper.readValue(result.text(), ReceiptAiOutput.class);
            if (!"success".equalsIgnoreCase(output.status())) {
                log.info("영수증 추출 실패 판정 reason={}", output.failReason());
                throw new CustomException(ErrorCode.FAIL_RECEIPT_EXTRACT,
                        output.failReason() == null ? ErrorCode.FAIL_RECEIPT_EXTRACT.getMessage() : output.failReason());
            }
            return output.toExtractedData();
        } catch (JsonProcessingException e) {
            log.error("영수증 JSON 파싱 오류: {}", e.getOriginalMessage());
            throw new CustomException(ErrorCode.FAIL_RECEIPT_EXTRACT);
        } catch (AiOutputException e) {
            log.warn("영수증 AI 응답 사용 불가 reason={}", e.getReason());
            throw new CustomException(e.getReason() == AiOutputException.Reason.BLOCKED
                    ? ErrorCode.AI_BLOCKED_CONTENT : ErrorCode.FAIL_RECEIPT_EXTRACT);
        } catch (UpstreamRejectedException e) {
            // 400/401/402/403: 재시도해도 같은 결과. 키, 결제, 요청 형식 문제이므로 운영자가 봐야 한다
            log.error("영수증 AI 호출 거부 status={} authError={}", e.getStatus(), e.isAuthError());
            throw new CustomException(ErrorCode.AI_UNAVAILABLE);
        } catch (RuntimeException e) {
            // 서킷 열림, 벌크헤드 거절, 타임아웃, 5xx, 429 등: 사용자에게는 모두 "일시 불가"
            if (ExternalFailures.isUnavailable(e)) {
                log.warn("영수증 AI 호출 일시 불가 cause={}", e.getClass().getSimpleName());
                throw new CustomException(ErrorCode.AI_UNAVAILABLE);
            }
            throw e;
        }
    }

    private ClaudeImage encode(MultipartFile image) {
        String type = image.getContentType() == null ? "" : image.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_TYPES.contains(type) || image.getSize() > aiProperties.maxImageBytes()) {
            throw new CustomException(ErrorCode.AI_INVALID_IMAGE);
        }
        try {
            return new ClaudeImage(type, ImageEncoder.encodeBase64Streaming(image));
        } catch (IOException e) {
            log.error("이미지 변환 실패");
            throw new CustomException(ErrorCode.FAIL_RECEIPT_EXTRACT);
        }
    }
}
