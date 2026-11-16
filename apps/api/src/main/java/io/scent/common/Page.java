package io.scent.common;

import java.util.List;

public record Page<T>(List<T> items, long total, int page, int pageSize) {
    public static int offset(int page, int pageSize) {
        if (page < 1 || page > 10000 || pageSize < 1 || pageSize > 40) {
            throw BusinessException.invalid("页码应为1..10000，每页1..40项");
        }
        return (page - 1) * pageSize;
    }

    public static String keyword(String text) {
        if (text == null || text.isBlank()) { return null; }
        if (text.length() > 100) { throw BusinessException.invalid("搜索关键词过长"); }
        return text.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
