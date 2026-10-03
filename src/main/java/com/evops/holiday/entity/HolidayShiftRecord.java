package com.evops.holiday.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 顺延记录行：一张被处理的派单一行；原档期/新档期全留底（顺延过的单子留原档期痕迹）。
 * 状态 SHIFTED 已顺延 / FAILED 顺延失败（队档期冲突等，单留原档期，人工改期）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_holiday_shift_record")
public class HolidayShiftRecord extends BaseEntity {
    private Long batchId;
    private String batchNo;
    private Long dispatchId;
    private String dispatchNo;
    /** 来源上刊单号（快照） */
    private String orderNo;
    private String siteNo;
    /** 安装队编号（待匹配单为空） */
    private String teamNo;
    /** 原档期（顺延前计划时段，扯皮有据可查） */
    private LocalDateTime oldPlanStart;
    private LocalDateTime oldPlanEnd;
    /** 新档期（顺延失败时为空） */
    private LocalDateTime newPlanStart;
    private LocalDateTime newPlanEnd;
    /** SHIFTED 已顺延 / FAILED 顺延失败 */
    private String status;
    /** 失败原因（如 队档期冲突） */
    private String failReason;
}
