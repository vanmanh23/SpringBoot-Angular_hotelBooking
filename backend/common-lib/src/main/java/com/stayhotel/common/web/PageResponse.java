package com.stayhotel.common.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Stable pagination envelope (API_CONTRACT.md): never expose Spring's Page JSON directly. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
