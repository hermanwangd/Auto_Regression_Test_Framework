package com.specdriven.regression.provider.wiremock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;

class WireMockJournalTest {
    @Test
    void persistsOnlyMethodAndPathForTheCorrelatedRequest() throws Exception {
        var mapper = new ObjectMapper();
        var journal = mapper.readTree("""
                {"requests":[
                  {"request":{"url":"/orders?view=summary","method":"GET","headers":{"x-regress-request-id":["ours"]},
                     "body":"sample body","bodyAsBase64":"sample encoding","cookies":{"session":"sample cookie"}}},
                  {"request":{"url":"/orders?view=summary","method":"GET","headers":{"X-Regress-Request-Id":"theirs"}}},
                  {"request":{"url":"/orders?view=detail","method":"GET","headers":{"X-Regress-Request-Id":"ours"}}},
                  {"request":{"url":"/orders?view=summary","method":"HEAD","headers":{"X-Regress-Request-Id":"ours"}}}
                ]}
                """);
        URI target = URI.create("http://localhost/orders?view=summary");

        var matches = WireMockJournal.matchedRequests(journal, "GET", target, "ours");
        assertThat(matches.size()).isOne();
        assertThat(matches.get(0).path("body").asText()).isEqualTo("sample body");
        var evidence = mapper.readTree(WireMockJournal.evidence(journal, "GET", target, "ours"));
        assertThat(evidence).isEqualTo(mapper.readTree("""
                {"requests":[{"request":{"url":"/orders","method":"GET"}}]}
                """));
        assertThatThrownBy(() -> WireMockJournal.evidence(journal, "GET", target, "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
