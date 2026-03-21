package com.diff.standalone.web.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * DAO 分页查询结果包装。
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private long total;

    private int pageNo;

    private int pageSize;

    @Builder.Default
    private List<T> items = Collections.emptyList();
}

