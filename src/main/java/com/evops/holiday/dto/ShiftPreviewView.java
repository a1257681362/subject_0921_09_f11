package com.evops.holiday.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 假期顺延预览：计划落在休息日的在途派单，逐单给出顺延后的新档期与可挪性预检。 */
@Data
public class ShiftPreviewView {
    /** 顺延依据的日历与版本 */
    private Long calendarId;
    private String calendarNo;
    private Integer calYear;
    private Long versionId;
    private Integer versionNo;
    /** 命中（计划落在休息日）的在途派单数 */
    private int hitCount;
    /** 预检可顺延数 */
    private int feasibleCount;
    /** 预检不可顺延数（队档期冲突等，执行时会标 FAILED 不硬挪） */
    private int blockedCount;
    private List<ShiftPreviewItem> items;

    @Data
    public static class ShiftPreviewItem {
        private String dispatchNo;
        private String orderNo;
        private String siteNo;
        private String teamNo;
        private String teamName;
        /** 派单状态 PENDING 待匹配 / ASSIGNED 已派单 */
        private String dispatchStatus;
        /** 原档期 */
        private LocalDateTime oldPlanStart;
        private LocalDateTime oldPlanEnd;
        /** 顺延后新档期（整体平移，时长与时刻不变） */
        private LocalDateTime newPlanStart;
        private LocalDateTime newPlanEnd;
        /** 命中的休息日（计划覆盖日期中落在生效日历休息日的那些天） */
        private List<LocalDate> restDays;
        /** 预检是否可顺延 */
        private boolean feasible;
        /** 不可顺延原因（如 队档期冲突） */
        private String blockReason;
    }
}
