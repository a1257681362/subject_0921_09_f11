package com.evops.holiday.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 日历条目规格：一天一条。dayType=REST 休息日 / WORK 调休上班日，两类分开维护。
 */
@Data
public class HolidayDaySpec {
    @NotNull(message = "日期不能为空")
    private LocalDate date;
    /** REST 休息日 / WORK 调休上班日 */
    @NotNull(message = "日类型不能为空（REST 休息日 / WORK 调休上班日）")
    private String type;
    /** 节假日/调休名称（可选，留痕展示用） */
    private String name;
}
