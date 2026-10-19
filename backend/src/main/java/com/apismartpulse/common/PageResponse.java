package com.apismartpulse.common;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

  public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
    return new PageResponse<>(items, page, size, total, (int) ((total + size - 1) / size));
  }

  public <R> PageResponse<R> map(Function<T, R> fn) {
    return new PageResponse<>(items.stream().map(fn).toList(), page, size, totalItems, totalPages);
  }

  /** Validates page/size per §2.8 (page ≥ 0, size 1–100). */
  public static void checkPaging(int page, int size) {
    if (page < 0) throw ApiException.validation("page", "must be ≥ 0");
    if (size < 1 || size > 100) throw ApiException.validation("size", "must be between 1 and 100");
  }
}
