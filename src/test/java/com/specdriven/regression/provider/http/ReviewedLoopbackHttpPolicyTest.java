package com.specdriven.regression.provider.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewedLoopbackHttpPolicyTest {
    @ParameterizedTest
    @MethodSource("acceptedTargets")
    void permitsReviewedFiniteTargets(String target) {
        assertThatCode(() -> validate(target)).doesNotThrowAnyException();
    }

    static Stream<String> acceptedTargets() {
        return Stream.of("/", "/api/items", "/items?quantity__lt=5&_sort=id&_shape=array",
                "/articles?views:gt=10&_sort=-views&_page=1&_per_page=1",
                "/items?title=hello+world&other=hello%20world&empty=",
                "/items?%61=1&A=2&sort=id%2Cname&plus=%2B",
                "/items?k=" + "x".repeat(512), "/items?" + "k".repeat(128) + "=v",
                "/items?" + pairs(32), "/" + "a".repeat(2043) + "?k=v");
    }

    @ParameterizedTest
    @MethodSource("rejectedTargets")
    void rejectsUnreviewableOrUnboundedTargets(String target) {
        assertThatThrownBy(() -> validate(target)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewed_http:");
    }

    static Stream<String> rejectedTargets() {
        return Stream.of("/items?", "/items?key", "/items?=value", "/items?a=1&&b=2",
                "/items?a=1&", "/items?a=b=c", "/items?a=1&%61=2", "/items?a=1&a=2",
                "/items?api%5Fkey=x", "/items?access_token=x", "/items?Password:gt=x",
                "/items?SESSIONID=x", "/items?connection=x", "/items?AUTH=x",
                "/items?key=%", "/items?key=%0", "/items?key=%GG", "/items?key=%C3%28",
                "/items?key=%C0%AF", "/items?key=%00", "/items?key=%0A", "/items?key=%7F",
                "/items?key=%E4%B8%AD", "/items?key=中", "/items?key=%2520", "/items?key=%26",
                "/items?key=%3D", "/items?key=%23", "/items?key=%24%7BENV%7D", "/items?key=${ENV}",
                "/items?key=hello world", "/items?key=1#fragment", "/items#fragment", "//host/items?k=v",
                "/a/../items?k=v", "/%69tems?k=v", "/items?key=" + "x".repeat(513),
                "/items?" + "k".repeat(129) + "=v", "/items?" + pairs(33),
                "/" + "a".repeat(2044) + "?k=v");
    }

    @ParameterizedTest
    @ValueSource(strings={"http://127.0.0.1:8080?k=v", "http://127.0.0.1:8080/#fragment",
            "http://localhost:8080", "http://127.0.0.1:8080/"})
    void querySupportDoesNotRelaxOrigin(String origin) {
        assertThatThrownBy(() -> ReviewedLoopbackHttpPolicy.validateOrigin(origin))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String pairs(int count) {
        return IntStream.range(0,count).mapToObj(i -> "k" + i + "=v").collect(Collectors.joining("&"));
    }

    private void validate(String target) {
        ReviewedLoopbackHttpPolicy.validateInputs(Map.of("request.method", "GET", "request.path", target));
    }
}
