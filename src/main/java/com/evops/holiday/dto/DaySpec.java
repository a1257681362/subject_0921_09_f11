package com.evops.holiday.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/** 假期日历日期行：休息日 REST / 调休上班日 MAKEUP_WORK 分开维护。 */
@Data
public class DaySpec {
    /** 日期（yyyy-MM-dd），必须落在导入年份内 */
    @NotNull(message = "日期不能为空")
    private LocalDate date;

    /** REST 休息日 / MAKEUP_WORK 调休上班日 */
    @NotBlank(message = "日期类型不能为空（REST 休息日 / MAKEUP_WORK 调休上班日）")
    private String kind;

    /** 节日名（如 国庆节），调休上班日可记调休说明 */
    private String name;
}
