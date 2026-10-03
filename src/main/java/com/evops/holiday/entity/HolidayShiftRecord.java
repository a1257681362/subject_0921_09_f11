package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 顺延批次逐单记录（只追加）：一张命中单一行。
 * 原档期/新档期/撞档占位单号/安装联动结果全留底，扯皮时按行翻账；
 * 单据上的最早原档期另在 t_posting_order.origin_* 只写一次。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_shift_record")
public class HolidayShiftRecord extends BaseEntity {
    private Long batchId;
    private String batchNo;
    private Long orderId;
    private String orderNo;
    private String siteNo;
    private String creativeName;
    /** 预览当时的档期（确认时据此 CAS 校验，防跨批次重复挪/单据已变） */
    private LocalDate oldOccurDate;
    private LocalDate oldWeekStart;
    private LocalDate oldWeekEnd;
    private LocalDate newOccurDate;
    private LocalDate newWeekStart;
    private LocalDate newWeekEnd;
    /** 命中的休息日名称（如 国庆节） */
    private String hitRestName;
    /** 目标周被哪张单占用（非空=预览即标冲突，确认时占位仍在则 SKIPPED） */
    private String conflictOrderNo;
    /** NONE / RESCHEDULED / TO_PENDING / NOT_DONE */
    private String installAction;
    private String installDispatchNo;
    /** PLANNED / SHIFTED / SKIPPED */
    private String status;
    private String skipReason;
}
