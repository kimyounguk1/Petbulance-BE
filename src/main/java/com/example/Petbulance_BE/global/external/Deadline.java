package com.example.Petbulance_BE.global.external;

import java.time.Duration;

/**
 * 요청 하나가 쓸 수 있는 전체 시간 상한.
 * 개별 호출의 타임아웃과 재시도가 순차 호출에서 곱해지면 사용자(또는 ALB idle timeout)가 먼저 끊기 때문에,
 * 다음 호출을 시작하기 전에 남은 시간을 확인한다.
 */
public final class Deadline {

    private final long expiresAtNanos;

    private Deadline(long expiresAtNanos) {
        this.expiresAtNanos = expiresAtNanos;
    }

    public static Deadline after(Duration duration) {
        return new Deadline(System.nanoTime() + duration.toNanos());
    }

    public Duration remaining() {
        long left = expiresAtNanos - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    public boolean hasAtLeast(Duration needed) {
        return remaining().compareTo(needed) >= 0;
    }
}
