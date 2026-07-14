package com.ssuai.domain.library.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ssuai.domain.library.dto.LibraryBookSearchResponse;
import com.ssuai.global.exception.ConnectorException;

@Service
public class LibraryBookService {

    private static final Logger log = LoggerFactory.getLogger(LibraryBookService.class);

    static final int MAX_QUERY_LENGTH = 64;
    static final int MAX_PAGE_SIZE = 20;
    static final int DEFAULT_PAGE_SIZE = 10;

    private final LibraryBookCache cache;

    public LibraryBookService(LibraryBookCache cache) {
        this.cache = cache;
    }

    public LibraryBookSearchResponse search(String query, Integer page, Integer size) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("검색어를 입력해 주세요.");
        }
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "검색어는 " + MAX_QUERY_LENGTH + "자 이하로 입력해 주세요.");
        }

        int effectivePage = page == null ? 0 : page;
        if (effectivePage < 0) {
            throw new IllegalArgumentException("page는 0 이상이어야 해요.");
        }

        int effectiveSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (effectiveSize < 1) {
            throw new IllegalArgumentException("size는 1 이상이어야 해요.");
        }
        if (effectiveSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size는 " + MAX_PAGE_SIZE + " 이하여야 해요.");
        }

        try {
            LibraryBookSearchResponse upstream = cache.get(trimmed, effectivePage, effectiveSize);
            // Connector responses must not be able to make page metadata disagree with the
            // validated request that produced this result.
            return new LibraryBookSearchResponse(
                    upstream.total(), effectivePage, effectiveSize, upstream.items());
        } catch (ConnectorException exception) {
            log.warn("library book search failure: queryLen={} page={} size={} code={}",
                    trimmed.length(), effectivePage, effectiveSize,
                    exception.getErrorCode().name());
            throw exception;
        }
    }
}
