package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 档期顺延批次：一次“预览 → 确认批量顺延”一批。
 * 预览即落 PREVIEWED 批次作为确认句柄（含命中范围与依据日历版本），确认置 CONFIRMED；
 * 命中/顺延/跳过/拦截（到场完工不挪）计数与逐单记录（见 {@link HolidayShiftRecord}）对账。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_shift_batch")
public class HolidayShiftBatch extends BaseEntity {
    /** 批次号 HS+序列，全局唯一 */
    private String batchNo;
    /** 命中范围涉及的年份（取范围起点所在年，仅展示） */
    private Integer calYear;
    /** 预览当时生效日历版本（依据固化） */
    private Long calendarVersionId;
    private Integer calendarVersionNo;
    private LocalDate scopeFrom;
    private LocalDate scopeTo;
    /** 限定点位编号，空=全部点位 */
    private String siteNo;
    /** PREVIEWED / CONFIRMED */
    private String status;
    private Integer hitCount;
    private Integer shiftedCount;
    private Integer skippedCount;
    /** 到场/完工安装派单联动拦截，不进挪单的单据数 */
    private Integer blockedCount;
    private String reason;
    private String operatorName;
    private LocalDateTime confirmTime;
    private String confirmOperator;
}
