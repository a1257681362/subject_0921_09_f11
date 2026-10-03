package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 假期日历版本：按年导入即起草稿（已有草稿整表覆盖）；发布生效，旧生效版自动 SUPERSEDED。
 * 休息日/调休上班日条数、经手人、发布时刻、导入说明随版本留底，版本链即操作记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_calendar_version")
public class HolidayCalendarVersion extends BaseEntity {
    private Long calendarId;
    private Integer versionNo;
    /** DRAFT 编制中 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代 */
    private String status;
    /** 休息日条数 */
    private Integer restCount;
    /** 调休上班日条数 */
    private Integer makeupCount;
    /** 导入说明 */
    private String changeSummary;
    private LocalDateTime publishTime;
    private String operatorName;
    private String remark;
}
