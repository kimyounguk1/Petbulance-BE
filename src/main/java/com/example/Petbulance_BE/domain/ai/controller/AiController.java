package com.example.Petbulance_BE.domain.ai.controller;

import com.example.Petbulance_BE.domain.ai.dto.res.DiagnosisResDto;
import com.example.Petbulance_BE.domain.ai.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/ai")
@Slf4j
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @PostMapping("/diagnosis")
    public DiagnosisResDto aiDiagnosis(@RequestParam(name = "images") List<MultipartFile> images,
    @RequestParam(name = "animalType", required = false)String animalType, @RequestParam(name = "symptom", required = false)String symptom) {
        return aiService.aiDiagnosisProcess(images, animalType, symptom);
    }

    /**
     * 스트리밍 진단 (SSE). 응급도, 의심 질환, 응급처치 단계를 생성되는 대로 보낸다.
     * 이벤트: field / step / result / error (최종 판단은 result 로 한다)
     */
    @PostMapping("/diagnosis/sse")
    public SseEmitter aiDiagnosisSse(@RequestParam(name = "images", required = false) List<MultipartFile> images,
                                     @RequestParam(name = "animalType", required = false) String animalType,
                                     @RequestParam(name = "symptom", required = false) String symptom) {
        return aiService.aiDiagnosisStream(images, animalType, symptom);
    }

    // 아래 두 개는 외부 호출 없이 이미지 인코딩 부하만 보는 테스트용 목 엔드포인트다
    @PostMapping("/diagnosis/stream")
    public DiagnosisResDto aiDiagnosis1(@RequestParam(name = "images") List<MultipartFile> images,
                                             @RequestParam(name = "animalType", required = false)String animalType, @RequestParam(name = "symptom", required = false)String symptom) {
        return aiService.aiDiagnosisProcess1(images, animalType, symptom);
    }

    @PostMapping("/diagnosis/encode")
    public DiagnosisResDto aiDiagnosis2(@RequestParam(name = "images") List<MultipartFile> images,
                                             @RequestParam(name = "animalType", required = false)String animalType, @RequestParam(name = "symptom", required = false)String symptom) {
        return aiService.aiDiagnosisProcess2(images, animalType, symptom);
    }

}
