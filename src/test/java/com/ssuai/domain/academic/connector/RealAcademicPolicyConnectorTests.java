package com.ssuai.domain.academic.connector;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class RealAcademicPolicyConnectorTests {

    @Test
    void parseTextExtractsLawContentWithoutScripts() {
        String html = """
                <html><body>
                <script>ignored()</script>
                <div id="lawcontent"><div class="lawname">학칙시행세칙</div><p>제1조 목적 졸업요건을 정한다.</p></div>
                </body></html>
                """;

        String text = RealAcademicPolicyConnector.parseText(Jsoup.parse(html));

        assertThat(text).contains("학칙시행세칙").contains("졸업요건");
        assertThat(text).doesNotContain("ignored");
    }
}
