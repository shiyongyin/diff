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

    /** 全部记录数，用于 pagination 统计。 */
    private long total;

    /** 当前页码，从 1 开始。 */
    private int pageNo;

    /** 每页条数。 */
    private int pageSize;

    @Builder.Default
    /** 当前页的结果列表（默认空 list，避免 null）。 */
    private List<T> items = Collections.emptyList();
}
