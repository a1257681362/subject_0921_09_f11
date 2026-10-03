package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 日历条目（随版本冻结）：一天一行，REST 休息日 / WORK 调休上班日分开维护、同日互斥。
 * 版本里没有的日期回退自然周（周一~五工作、周六日休）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_day")
public class HolidayDay extends BaseEntity {
    private Long versionId;
    private Integer calYear;
    private LocalDate holiDate;
    /** REST 休息日 / WORK 调休上班日 */
    private String dayType;
    /** 节假日名称，如 国庆节、中秋节；调休上班日可填“国庆调休上班” */
    private String holidayName;
}
