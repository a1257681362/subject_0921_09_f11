package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 假期日历版本（只追加留痕）：按年导入/手工配置/发布逐版留底，版本链即操作记录。
 * 来源 IMPORT 按年导入 / MANUAL 手工配置；状态 DRAFT / EFFECTIVE / SUPERSEDED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_calendar_version")
public class HolidayCalendarVersion extends BaseEntity {
    private Long calendarId;
    private Integer versionNo;
    /** DRAFT / EFFECTIVE / SUPERSEDED */
    private String status;
    /** IMPORT / MANUAL */
    private String source;
    /** 调整/导入说明（操作记录留底） */
    private String changeSummary;
    private Integer restCount;
    private Integer workCount;
    private LocalDateTime publishTime;
    private String operatorName;
    private String remark;
}
