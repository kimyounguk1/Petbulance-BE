package com.example.Petbulance_BE.domain.ai.dto;

import java.util.List;

/** Claude 진단 응답(JSON 스키마 고정)을 받는 DTO. 스키마는 resources/ai/diagnosis-schema.json */
public class AiDiagnosisDto {

    public record Step(
            String description,
            String warning
    ) {}

    /** 모델 출력 전체. status=fail 이면 failReason 만 의미가 있다 */
    public record DiagnosisAiOutput(
            String status,
            String failReason,
            String animalType,
            String emergencyLevel,
            String suspectedDisease,
            Double confidence,
            List<String> detectedSymptoms,
            List<String> recommendedActions,
            List<Step> steps
    ) {}
}
