package com.example.Petbulance_BE.global.external.claude;

/** 스트리밍하지 않는 호출의 결과. stop_reason 검사를 통과한 것만 만들어진다 */
public record ClaudeResult(String text, String stopReason, Integer inputTokens, Integer outputTokens) {
}
