package com.example.Petbulance_BE.domain.ai.service;

import com.example.Petbulance_BE.domain.ai.config.AiProperties;
import com.example.Petbulance_BE.domain.ai.dto.AiDiagnosisDto.DiagnosisAiOutput;
import com.example.Petbulance_BE.domain.ai.dto.res.DiagnosisResDto;
import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;
import com.example.Petbulance_BE.global.external.Deadline;
import com.example.Petbulance_BE.global.external.ExternalFailures;
import com.example.Petbulance_BE.global.external.claude.AiOutputException;
import com.example.Petbulance_BE.global.external.claude.ClaudeClient;
import com.example.Petbulance_BE.global.external.claude.ClaudeImage;
import com.example.Petbulance_BE.global.external.claude.ClaudeStream;
import com.example.Petbulance_BE.global.external.claude.PromptResources;
import com.example.Petbulance_BE.global.external.claude.StreamCancelledException;
import com.example.Petbulance_BE.global.external.claude.StreamingJsonFieldParser;
import com.example.Petbulance_BE.global.external.exception.UpstreamRejectedException;
import com.example.Petbulance_BE.global.external.exception.UpstreamTimeoutException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 진단: 사진(최대 3장) + 동물 종 + 증상 → Claude 한 번 호출 → 응급도, 의심 질환, 응급처치 단계.
 *
 * <p>설계 요점
 * <ul>
 *   <li>예전에는 Gemini(이미지 분석) → Vertex(진단) 두 번 호출했다. Claude 는 사진과 텍스트를 한 번에 받으므로 1회로 줄였다</li>
 *   <li>스트리밍: 출력 JSON 을 조각으로 받으면서 필드가 완성되는 즉시 사용자에게 보낸다 (응급도가 가장 먼저)</li>
 *   <li>전용 스레드: Tomcat 스레드는 입력 검사 후 바로 반환되고, 진단은 AI 전용 스레드풀에서 돈다. 풀이 차면 즉시 503</li>
 *   <li>재시도는 스트림을 열기 전(아무것도 보내기 전)에만 한다. 보내기 시작한 뒤에는 재시도하지 않는다</li>
 *   <li>사용자가 떠나면 Claude 스트림을 바로 끊는다. "동물 사진이 아님" 판정도 나오는 즉시 끊어 토큰을 아낀다</li>
 *   <li>모델 출력은 외부 입력이다: 스키마로 형식을 고정하고, 서버에서 값(응급도, 신뢰도, 단계)을 다시 검증한다</li>
 * </ul>
 */
@Service
@Slf4j
public class AiService {

    static final String CIRCUIT = "claude";
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Set<String> EMERGENCY_LEVELS = Set.of("high", "middle", "low");

    private final ClaudeClient claudeClient;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final ExecutorService aiStreamExecutor;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final String systemPrompt;
    private final JsonNode outputSchema;

    public AiService(ClaudeClient claudeClient,
                     ObjectMapper objectMapper,
                     AiProperties aiProperties,
                     @Qualifier("aiStreamExecutor") ExecutorService aiStreamExecutor,
                     CircuitBreakerRegistry circuitBreakerRegistry,
                     RetryRegistry retryRegistry) {
        this.claudeClient = claudeClient;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        this.aiStreamExecutor = aiStreamExecutor;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.systemPrompt = PromptResources.text("ai/diagnosis-system-prompt.txt");
        this.outputSchema = PromptResources.json(objectMapper, "ai/diagnosis-schema.json");
    }

    // ================================================================ 진입점

    /** SSE 스트리밍 진단. 입력 오류도 error 이벤트로 보낸다 (SSE 클라이언트가 한 가지 방식으로만 처리하도록) */
    public SseEmitter aiDiagnosisStream(List<MultipartFile> images, String animalType, String symptom) {
        SseEmitter emitter = new SseEmitter(aiProperties.flowDeadline().plusSeconds(5).toMillis());
        SseDiagnosisListener listener = new SseDiagnosisListener(emitter);
        emitter.onTimeout(listener::cancel);
        emitter.onError(e -> listener.cancel());
        emitter.onCompletion(listener::cancel);

        try {
            DiagnosisInput input = prepare(images, animalType, symptom);
            aiStreamExecutor.execute(() -> runDiagnosis(input, listener));
        } catch (CustomException e) {
            listener.onError(e);
        } catch (RejectedExecutionException e) {
            log.warn("AI 진단 전용 스레드가 모두 사용 중");
            listener.onError(new CustomException(ErrorCode.AI_UNAVAILABLE));
        }
        return emitter;
    }

    /** 기존 JSON API. 같은 진단 로직을 전용 스레드에서 돌리고 결과만 모아 반환한다 (앱 계약 유지) */
    public DiagnosisResDto aiDiagnosisProcess(List<MultipartFile> images, String animalType, String symptom) {
        DiagnosisInput input = prepare(images, animalType, symptom);
        CollectingDiagnosisListener listener = new CollectingDiagnosisListener();

        Future<?> future;
        try {
            future = aiStreamExecutor.submit(() -> runDiagnosis(input, listener));
        } catch (RejectedExecutionException e) {
            log.warn("AI 진단 전용 스레드가 모두 사용 중");
            throw new CustomException(ErrorCode.AI_UNAVAILABLE);
        }

        try {
            future.get(aiProperties.flowDeadline().plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            listener.cancel();
            throw new CustomException(ErrorCode.AI_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.cancel();
            throw new CustomException(ErrorCode.AI_UNAVAILABLE);
        } catch (ExecutionException e) {
            log.error("AI 진단 실행 중 예상치 못한 오류", e.getCause());
            throw new CustomException(ErrorCode.AI_DIAGNOSIS_FAIL);
        }
        return listener.resultOrThrow();
    }

    // ================================================================ 입력

    record DiagnosisInput(List<ClaudeImage> images, String userText) {
    }

    /** 요청 스레드에서 실행: 검증 + 이미지 인코딩 (멀티파트 파일은 요청이 끝나면 사라질 수 있어 미리 읽는다) */
    DiagnosisInput prepare(List<MultipartFile> images, String animalType, String symptom) {
        if (!aiProperties.isEnabled()) {
            log.warn("AI 진단 비활성화 상태(킬스위치)");
            throw new CustomException(ErrorCode.AI_UNAVAILABLE);
        }

        List<MultipartFile> files = images == null ? List.of()
                : images.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (files.size() > aiProperties.maxImages()) {
            throw new CustomException(ErrorCode.AI_TOO_MANY_IMAGES,
                    "AI 진단 이미지는 최대 " + aiProperties.maxImages() + "장까지 가능합니다.");
        }

        String animal = normalizeText(animalType, 50, "동물 종");
        String symptoms = normalizeText(symptom, aiProperties.maxSymptomLength(), "증상");

        List<ClaudeImage> encoded = new ArrayList<>();
        for (MultipartFile file : files) {
            String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
            if (!ALLOWED_IMAGE_TYPES.contains(type) || file.getSize() > aiProperties.maxImageBytes()) {
                throw new CustomException(ErrorCode.AI_INVALID_IMAGE);
            }
            try {
                encoded.add(new ClaudeImage(type, Base64.getEncoder().encodeToString(file.getBytes())));
            } catch (IOException e) {
                throw new CustomException(ErrorCode.IMAGE_PROCESSING_ERROR);
            }
        }

        // 사용자 입력은 태그로 감싸 "데이터"임을 분명히 한다 (프롬프트 인젝션 완화). 태그 탈출을 막기 위해 꺾쇠는 치환
        String userText = "<animal_type>" + animal + "</animal_type>\n"
                + "<symptoms>" + symptoms + "</symptoms>\n"
                + "첨부 사진: " + encoded.size() + "장";
        return new DiagnosisInput(encoded, userText);
    }

    private static String normalizeText(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return "입력없음";
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new CustomException(ErrorCode.INVALID_REQUEST, label + "은(는) " + maxLength + "자 이하로 입력해주세요.");
        }
        return trimmed.replace('<', '＜').replace('>', '＞');
    }

    // ================================================================ 진단 실행 (전용 스레드)

    /**
     * 스트림은 수명이 길어서 어노테이션 대신 서킷을 직접 다룬다.
     * 스트림 중간에 난 오류(과부하 등)까지 서킷에 반영하기 위해서다.
     */
    void runDiagnosis(DiagnosisInput input, DiagnosisListener listener) {
        Deadline deadline = Deadline.after(aiProperties.flowDeadline());
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT);
        long start = System.nanoTime();
        boolean permitted = false;

        try {
            circuitBreaker.acquirePermission(); // 열려 있으면 CallNotPermittedException
            permitted = true;

            ObjectNode body = claudeClient.buildRequest(systemPrompt, input.images(), input.userText(), outputSchema);
            // 재시도는 여기(스트림을 여는 단계)에서만. 아직 사용자에게 아무것도 보내지 않았다
            ClaudeStream stream = Retry.decorateSupplier(retryRegistry.retry(CIRCUIT),
                    () -> claudeClient.openStream("diagnosis", body)).get();
            listener.attach(stream);

            StringBuilder fullText = new StringBuilder();
            StreamingJsonFieldParser parser = new StreamingJsonFieldParser(
                    objectMapper, Set.of("steps"), new FieldForwarder(listener));

            ClaudeStream.StreamSummary summary = stream.read(chunk -> {
                if (listener.isCancelled()) {
                    throw new StreamCancelledException();
                }
                if (deadline.remaining().isZero()) {
                    throw new UpstreamTimeoutException(CIRCUIT, "진단 흐름 deadline 초과", null);
                }
                fullText.append(chunk);
                parser.feed(chunk);
            });

            circuitBreaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            permitted = false;

            AiOutputException.checkStopReason(summary.stopReason(), fullText);
            listener.onResult(toValidatedResult(fullText.toString()));

        } catch (NotAnimalImage e) {
            // 모델이 정상적으로 "분석 불가"라고 답한 것: 상대 장애가 아니므로 성공으로 기록
            if (permitted) {
                circuitBreaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
            listener.onError(new CustomException(ErrorCode.BAD_IMAGE, e.getMessage()));
        } catch (StreamCancelledException e) {
            if (permitted) {
                circuitBreaker.releasePermission();
            }
            log.info("사용자 연결 종료로 Claude 스트림을 중단했습니다.");
        } catch (RuntimeException e) {
            if (permitted) {
                // 기록 여부는 yml 의 recordExceptions/ignoreExceptions 가 판단한다
                circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            }
            listener.onError(translate(e));
        } finally {
            listener.detach();
        }
    }

    /** 필드를 사용자에게 흘려보내면서, "동물 사진 아님" 판정은 나오는 즉시 스트림을 멈춘다 */
    private static final class FieldForwarder implements StreamingJsonFieldParser.Handler {

        private final DiagnosisListener listener;
        private String status;

        private FieldForwarder(DiagnosisListener listener) {
            this.listener = listener;
        }

        @Override
        public void onField(String name, JsonNode value) {
            switch (name) {
                case "status" -> status = value.asText();
                case "failReason" -> {
                    if ("fail".equalsIgnoreCase(status)) {
                        throw new NotAnimalImage(value.isNull() || value.asText().isBlank()
                                ? "분석할 수 없는 사진입니다." : value.asText());
                    }
                }
                // 문서상 enum 값의 대소문자는 보장되지 않으므로 정규화해서 보낸다
                case "emergencyLevel" -> listener.onField(name, TextNode.valueOf(value.asText().toLowerCase(Locale.ROOT)));
                default -> listener.onField(name, value);
            }
        }

        @Override
        public void onArrayElement(String arrayName, int index, JsonNode value) {
            listener.onStep(index, value);
        }
    }

    /** 모델이 "동물 사진이 아님/종 불일치"로 판정함 */
    private static final class NotAnimalImage extends RuntimeException {
        private NotAnimalImage(String message) {
            super(message);
        }
    }

    // ================================================================ 출력 검증

    /** 스키마로 형식은 보장되지만, 값의 의미(허용 값, 범위, 필수 내용)는 서버가 다시 확인한다 */
    DiagnosisResDto toValidatedResult(String json) {
        DiagnosisAiOutput out;
        try {
            out = objectMapper.readValue(json, DiagnosisAiOutput.class);
        } catch (JsonProcessingException e) {
            throw new AiOutputException(AiOutputException.Reason.INVALID_FORMAT, "진단 JSON 파싱 실패", e);
        }
        if (!"success".equalsIgnoreCase(out.status())) {
            throw new NotAnimalImage(out.failReason() == null ? "분석할 수 없는 사진입니다." : out.failReason());
        }

        String level = out.emergencyLevel() == null ? "" : out.emergencyLevel().toLowerCase(Locale.ROOT);
        if (!EMERGENCY_LEVELS.contains(level)) {
            throw new AiOutputException(AiOutputException.Reason.INVALID_FORMAT, "허용되지 않은 응급도: " + level);
        }
        if (out.confidence() != null && (out.confidence() < 0 || out.confidence() > 1)) {
            throw new AiOutputException(AiOutputException.Reason.INVALID_FORMAT, "신뢰도 범위 오류: " + out.confidence());
        }
        if (out.steps() == null || out.steps().isEmpty()) {
            throw new AiOutputException(AiOutputException.Reason.INVALID_FORMAT, "응급처치 단계가 없습니다.");
        }

        return DiagnosisResDto.builder()
                .animalType(out.animalType())
                .emergencyLevel(level)
                .confidence(out.confidence())
                .detectedSymptoms(out.detectedSymptoms())
                .suspectedDisease(out.suspectedDisease())
                .recommendedActions(out.recommendedActions())
                .steps(out.steps())
                .totalSteps(out.steps().size()) // 모델이 적은 개수가 아니라 실제 개수
                .build();
    }

    private CustomException translate(RuntimeException e) {
        if (e instanceof CustomException custom) {
            return custom;
        }
        if (e instanceof NotAnimalImage notAnimal) {
            return new CustomException(ErrorCode.BAD_IMAGE, notAnimal.getMessage());
        }
        if (e instanceof AiOutputException output) {
            log.warn("AI 출력 사용 불가 reason={}", output.getReason());
            return switch (output.getReason()) {
                case BLOCKED -> new CustomException(ErrorCode.AI_BLOCKED_CONTENT);
                case INVALID_FORMAT -> new CustomException(ErrorCode.AI_RESPONSE_PARSING_ERROR);
                case EMPTY, TRUNCATED -> new CustomException(ErrorCode.AI_DIAGNOSIS_FAIL);
            };
        }
        if (e instanceof UpstreamRejectedException rejected) {
            // 400/401/402/403: 키, 결제, 요청 형식 문제. 재시도해도 같으므로 운영자가 봐야 한다
            log.error("Claude 호출 거부 status={} authError={}", rejected.getStatus(), rejected.isAuthError());
            return new CustomException(ErrorCode.AI_UNAVAILABLE);
        }
        if (ExternalFailures.isUnavailable(e)) {
            log.warn("Claude 일시 불가 cause={}", e.getClass().getSimpleName());
            return new CustomException(ErrorCode.AI_UNAVAILABLE);
        }
        log.error("AI 진단 중 예상치 못한 오류", e);
        return new CustomException(ErrorCode.AI_DIAGNOSIS_FAIL);
    }

    // ================================================================ 부하 테스트용 목 (외부 호출 없음)

    public DiagnosisResDto aiDiagnosisProcess1(List<MultipartFile> images, String animalTypes, String symptoms) {
        return mockDiagnosis(images, animalTypes);
    }

    public DiagnosisResDto aiDiagnosisProcess2(List<MultipartFile> images, String animalTypes, String symptoms) {
        return mockDiagnosis(images, animalTypes);
    }

    private DiagnosisResDto mockDiagnosis(List<MultipartFile> images, String animalTypes) {
        if (images != null) {
            for (MultipartFile image : images) {
                try {
                    log.info("이미지 인코딩 완료 (길이: {}), 가짜 응답 반환",
                            Base64.getEncoder().encodeToString(image.getBytes()).length());
                } catch (IOException e) {
                    throw new CustomException(ErrorCode.IMAGE_PROCESSING_ERROR);
                }
            }
        }
        return DiagnosisResDto.builder()
                .animalType(animalTypes == null ? "입력없음" : animalTypes)
                .emergencyLevel("high")
                .detectedSymptoms(List.of("결막염", "안구 건조"))
                .suspectedDisease("안구 질환 의심")
                .totalSteps(0)
                .steps(new ArrayList<>())
                .recommendedActions(List.of("인공눈물 투여", "병원 방문"))
                .confidence(0.88)
                .build();
    }
}
