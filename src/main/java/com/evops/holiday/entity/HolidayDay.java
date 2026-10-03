package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 假期日历日期（随版本冻结）：休息日 REST / 调休上班日 MAKEUP_WORK 分开维护；
 * 同一版本内一个日期只能落一边（(version_id, day_date) 唯一）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_day")
public class HolidayDay extends BaseEntity {
    private Long versionId;
    private LocalDate dayDate;
    /** REST 休息日 / MAKEUP_WORK 调休上班日 */
    private String dayKind;
    /** 节日名（如 国庆节），调休上班日可记调休说明 */
    private String dayName;
}
