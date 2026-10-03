package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 顺延批次：一次"按生效日历顺延在途派单"一批，扫描/顺延/失败数与依据版本、经手人全留底。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_shift_batch")
public class HolidayShiftBatch extends BaseEntity {
    /** 批次号 HS+序列，全局唯一 */
    private String batchNo;
    /** 顺延依据的假期日历与版本（留痕） */
    private Long calendarId;
    private Long calendarVersionId;
    private Integer calendarVersionNo;
    private Integer calYear;
    /** 命中（计划落在休息日）的在途派单数 */
    private Integer scannedCount;
    /** 实际顺延成功数 */
    private Integer shiftedCount;
    /** 顺延失败数（队档期冲突等，单留原档期） */
    private Integer failedCount;
    /** 顺延原因（默认"假期日历调整，休息日顺延"） */
    private String reason;
    private String operatorName;
}
