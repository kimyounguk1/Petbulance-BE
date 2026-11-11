package com.example.Petbulance_BE.global.external.claude;

/** 우리 쪽에서 스트림을 끊었을 때 (클라이언트 이탈 등). 오류가 아니라 정상적인 중단이다 */
public class StreamCancelledException extends RuntimeException {

    public StreamCancelledException() {
        super("스트림이 취소되었습니다.");
    }
}
