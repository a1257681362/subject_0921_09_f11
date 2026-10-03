package com.evops.holiday.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 按年导入请求：整年份的休息日/调休上班日条目。
 * 先 /import/preview 比差异，确认后 /import/confirm 直接发布新版（旧版自动取代）。
 */
@Data
public class HolidayImportRequest {
    @NotNull(message = "导入条目列表不能为 null（空请传空数组）")
    @Valid
    private List<HolidayDaySpec> days;
    /** 导入说明（版本操作记录留底），如“国务院 2026 年节假日安排” */
    private String changeSummary;
}
