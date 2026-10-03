package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 假期日历主档：一个自然年一份（年份唯一），当前生效版本指针随发布更新。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_calendar")
public class HolidayCalendar extends BaseEntity {
    /** 日历编号 HC+序列，全局唯一 */
    private String calendarNo;
    /** 自然年（唯一） */
    private Integer calYear;
    private String calendarName;
    /** DRAFT 编制中 / EFFECTIVE 生效中 */
    private String status;
    private Long currentVersionId;
    private Integer currentVersionNo;
    private String operatorName;
    private String remark;
}
