package com.example.Petbulance_BE.domain.review.dto;

import java.util.List;

/** 영수증 인식(Claude) 결과 DTO. 스키마는 resources/ai/receipt-schema.json */
public class ReceiptAiDto {

    /** 모델 출력 전체. status=fail 이면 동물병원 영수증이 아니거나 추출 실패 */
    public record ReceiptAiOutput(
            String status,
            String failReason,
            String storeName,
            List<Item> items,
            Long totalAmount,
            String address,
            String addressType,
            String paymentTime
    ) {
        public ExtractedData toExtractedData() {
            return new ExtractedData(storeName, items, totalAmount, address, addressType, paymentTime);
        }
    }

    /** 영수증에서 뽑은 값 */
    public record ExtractedData(
            String storeName,
            List<Item> items,
            Long totalAmount,
            String address,
            String addressType,
            String paymentTime
    ) {}

    public record Item(
            String name,
            Integer price
    ) {}
}
