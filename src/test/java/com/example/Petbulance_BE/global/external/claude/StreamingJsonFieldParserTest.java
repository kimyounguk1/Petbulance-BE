package com.example.Petbulance_BE.global.external.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamingJsonFieldParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private StreamingJsonFieldParser parser() {
        return new StreamingJsonFieldParser(objectMapper, Set.of("steps"), new StreamingJsonFieldParser.Handler() {
            @Override
            public void onField(String name, JsonNode value) {
                events.add(name + "=" + value);
            }

            @Override
            public void onArrayElement(String arrayName, int index, JsonNode value) {
                events.add(arrayName + "[" + index + "]=" + value);
            }
        });
    }

    @Test
    @DisplayName("한 글자씩 들어와도 필드가 완성되는 순간마다 알려준다 (문자열 안의 괄호, 이스케이프, 한글 포함)")
    void emitsFieldsAsTheyComplete() {
        String json = "{\"title\":\"괄호 } { 와 \\\"따옴표\\\"\",\"n\":3,\"steps\":[{\"d\":\"하나\"},{\"d\":\"둘\"}],"
                + "\"meta\":{\"k\":[1,2]},\"x\":null}";
        StreamingJsonFieldParser parser = parser();

        List<Integer> sizesAfterEachChar = new ArrayList<>();
        for (char c : json.toCharArray()) {
            parser.feed(String.valueOf(c));
            sizesAfterEachChar.add(events.size());
        }

        assertThat(events).containsExactly(
                "title=\"괄호 } { 와 \\\"따옴표\\\"\"",
                "n=3",
                "steps[0]={\"d\":\"하나\"}",
                "steps[1]={\"d\":\"둘\"}",
                "meta={\"k\":[1,2]}",
                "x=null");
        // 첫 필드는 전체가 끝나기 훨씬 전에 나와야 한다
        assertThat(sizesAfterEachChar.indexOf(1)).isLessThan(json.length() / 2);
    }

    @Test
    @DisplayName("형식이 깨진 JSON 은 INVALID_FORMAT")
    void brokenJson() {
        StreamingJsonFieldParser parser = parser();

        assertThatThrownBy(() -> parser.feed("{\"a\": tru}"))
                .isInstanceOfSatisfying(AiOutputException.class,
                        e -> assertThat(e.getReason()).isEqualTo(AiOutputException.Reason.INVALID_FORMAT));
    }
}
