package com.example.Petbulance_BE.domain.ai.service;

import com.example.Petbulance_BE.domain.ai.dto.res.DiagnosisResDto;
import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;

/** 기존 JSON API(/ai/diagnosis)용: 중간 이벤트는 버리고 최종 결과만 모은다 */
class CollectingDiagnosisListener extends DiagnosisListener {

    private volatile DiagnosisResDto result;
    private volatile CustomException error;

    @Override
    public void onField(String name, JsonNode value) {
    }

    @Override
    public void onStep(int index, JsonNode step) {
    }

    @Override
    public void onResult(DiagnosisResDto result) {
        this.result = result;
    }

    @Override
    public void onError(CustomException error) {
        this.error = error;
    }

    DiagnosisResDto resultOrThrow() {
        if (error != null) {
            throw error;
        }
        if (result == null) {
            throw new CustomException(ErrorCode.AI_UNAVAILABLE);
        }
        return result;
    }
}
