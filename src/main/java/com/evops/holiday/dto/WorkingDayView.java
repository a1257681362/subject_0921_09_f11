package com.evops.holiday.dto;

import lombok.Data;

import java.time.LocalDate;

/** 工作日判定结果：某日期是工作日还是休息日，依据来自生效日历版本或自然周回退。 */
@Data
public class WorkingDayView {
    private LocalDate date;
    /** true 工作日 / false 休息日 */
    private Boolean working;
    /** REST 休息日 / WORK 调休上班日 / WEEKDAY 普通工作日 / WEEKEND 普通周末 */
    private String basis;
    private String basisLabel;
    /** 判定所依据的生效日历版本号；该年无生效版为 null（回退自然周） */
    private Integer calendarVersionNo;
    private String holidayName;
}
