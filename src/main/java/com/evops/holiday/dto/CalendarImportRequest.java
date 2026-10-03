package com.evops.holiday.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 按年导入假期日历：整表替换式导入该年的休息日/调休上班日。
 * 该年已有编制中草稿则整表覆盖草稿；导入即返回与当前生效版的差异比对，确认后再发布覆盖。
 */
@Data
public class CalendarImportRequest {
    /** 自然年（如 2026），一个自然年一份日历 */
    @NotNull(message = "导入年份不能为空")
    private Integer year;

    /** 整表日期行（休息日+调休上班日）；至少一行 */
    @NotNull(message = "日期列表不能为 null")
    @Valid
    private List<DaySpec> days;

    /** 导入说明（随版本留底，如「2026 年国务院节假日安排」） */
    private String changeSummary;

    private String remark;
}
