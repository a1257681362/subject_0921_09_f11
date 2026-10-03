package com.evops.holiday.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 顺延预览结果（落 PREVIEWED 批次后返回）：批次句柄 + 逐单行 + 拦截行。
 * 确认时拿 batchNo 调 /shifts/{batchNo}/confirm，服务端逐行重新校验当前态后批量执行。
 */
@Data
public class ShiftPreviewView {
    private String batchNo;
    private Integer calYear;
    private Integer calendarVersionNo;
    private LocalDate scopeFrom;
    private LocalDate scopeTo;
    private String siteNo;
    /** 可顺延命中数（含目标周冲突、确认时可能 SKIPPED） */
    private Integer hitCount;
    /** 安装派单已到场/完工而不挪的拦截数 */
    private Integer blockedCount;

    /** 命中待顺延长（确认后 SHIFTED 或 SKIPPED） */
    private List<ShiftRow> shifts = new ArrayList<>();
    /** 拦截行（不参与批量执行） */
    private List<ShiftRow> blocked = new ArrayList<>();

    @Data
    public static class ShiftRow {
        private Long orderId;
        private String orderNo;
        private String siteNo;
        private String creativeName;
        private LocalDate oldOccurDate;
        private LocalDate oldWeekStart;
        private LocalDate oldWeekEnd;
        private LocalDate newOccurDate;
        private LocalDate newWeekStart;
        private LocalDate newWeekEnd;
        private String hitRestName;
        /** 非空=目标周已被该单占用，确认时占位仍在则跳过 */
        private String conflictOrderNo;
        /** NONE / RESCHEDULED / TO_PENDING / NOT_DONE（预览为预估，确认以实际为准） */
        private String installAction;
        private String installDispatchNo;
        /** 拦截原因（blocked 行用），如“安装派单已到场打卡，不自动挪” */
        private String blockReason;
    }
}
