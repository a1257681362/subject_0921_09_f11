package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 假期日历主档：一个自然年一份（cal_year 唯一）。
 * 状态 DRAFT 编制中（有未发布草稿）/ EFFECTIVE 已发布生效；currentVersion* 指向当前生效版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_calendar")
public class HolidayCalendar extends BaseEntity {
    /** 日历编号 HC+序列，全局唯一 */
    private String calendarNo;
    /** 自然年，如 2026 */
    private Integer calYear;
    private String calendarName;
    /** DRAFT / EFFECTIVE */
    private String status;
    private Long currentVersionId;
    private Integer currentVersionNo;
    private String operatorName;
    private String remark;
}
