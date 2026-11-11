package com.example.Petbulance_BE.domain.ai.service;

import com.example.Petbulance_BE.domain.ai.dto.res.DiagnosisResDto;
import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.example.Petbulance_BE.global.external.claude.ClaudeStream;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 진단 진행 상황을 받는 쪽. SSE(바로 흘려보냄)와 JSON(다 모아서 한 번에 반환)이 같은 진단 로직을 쓴다.
 * 취소 시 연결된 Claude 스트림을 즉시 끊는다.
 */
public abstract class DiagnosisListener {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile ClaudeStream stream;

    /** 최상위 필드 하나가 완성됨 (예: emergencyLevel). 미리보기 용도 */
    public abstract void onField(String name, JsonNode value);

    /** 응급처치 단계 하나가 완성됨 */
    public abstract void onStep(int index, JsonNode step);

    /** 검증을 통과한 최종 결과 */
    public abstract void onResult(DiagnosisResDto result);

    public abstract void onError(CustomException error);

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 사용자가 떠났거나 시간이 초과됨: 진행 중인 Claude 스트림을 바로 끊는다. 어느 스레드에서 불러도 된다 */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            ClaudeStream current = stream;
            if (current != null) {
                current.close();
            }
        }
    }

    void attach(ClaudeStream stream) {
        this.stream = stream;
        if (cancelled.get()) {
            stream.close();
        }
    }

    void detach() {
        this.stream = null;
    }
}
