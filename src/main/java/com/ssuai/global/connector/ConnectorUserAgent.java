package com.ssuai.global.connector;

/**
 * 학교 공개 페이지를 크롤링하는 real connector가 공통으로 쓰는 User-Agent.
 * 운영자가 트래픽 출처를 확인할 수 있도록 저장소 URL로 식별한다.
 */
public final class ConnectorUserAgent {

    public static final String VALUE = "ssuMCP (+https://github.com/ghdtjdwn/ssuMCP)";

    private ConnectorUserAgent() {
    }
}
