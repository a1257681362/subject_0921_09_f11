package com.evops.holiday.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 档期顺延预览/确认请求：扫发生日在 [from,to] 内的挂起中上刊单。
 * siteNo 留空=全部点位。命中休息日即算下一工作日并预演周档期/撞档/安装联动。
 */
@Data
public class ShiftPreviewRequest {
    private LocalDate from;
    private LocalDate to;
    private String siteNo;
    /** 顺延原因（批次留底），如“2026 国庆假期安排调整” */
    private String reason;
}
