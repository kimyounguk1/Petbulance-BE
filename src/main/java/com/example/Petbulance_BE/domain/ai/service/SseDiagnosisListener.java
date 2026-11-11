package com.example.Petbulance_BE.domain.ai.service;

import com.example.Petbulance_BE.domain.ai.dto.res.DiagnosisResDto;
import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 진단 결과를 SSE 이벤트로 바로 흘려보낸다.
 *
 * <pre>
 * event: field   data: {"name":"emergencyLevel","value":"high"}   (필드가 완성될 때마다, 미리보기)
 * event: step    data: {"index":0,"step":{"description":...,"warning":...}}
 * event: result  data: DiagnosisResDto                              (검증을 통과한 최종 결과)
 * event: error   data: {"code":"AI_UNAVAILABLE","message":...}
 * </pre>
 * field/step 은 빠른 화면 표시용이고, 최종 판단은 반드시 result 로 한다.
 * 전송이 실패하면(사용자가 떠남) Claude 스트림을 끊어 토큰 낭비를 막는다.
 */
@Slf4j
class SseDiagnosisListener extends DiagnosisListener {

    private final SseEmitter emitter;

    SseDiagnosisListener(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onField(String name, JsonNode value) {
        send("field", Map.of("name", name, "value", value));
    }

    @Override
    public void onStep(int index, JsonNode step) {
        send("step", Map.of("index", index, "step", step));
    }

    @Override
    public void onResult(DiagnosisResDto result) {
        if (send("result", result)) {
            emitter.complete();
        }
    }

    @Override
    public void onError(CustomException error) {
        if (send("error", Map.of("code", error.getErrorCode().name(), "message", error.getMessage()))) {
            emitter.complete();
        }
    }

    private boolean send(String event, Object data) {
        if (isCancelled()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            // 클라이언트 연결이 끊김: 더 보낼 곳이 없으므로 Claude 스트림도 끊는다
            log.info("SSE 전송 실패, Claude 스트림 중단 cause={}", e.getClass().getSimpleName());
            cancel();
            return false;
        }
    }
}
