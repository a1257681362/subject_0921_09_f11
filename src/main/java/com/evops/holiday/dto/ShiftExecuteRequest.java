package com.evops.holiday.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.List;

/** 确认批量顺延：按某年生效日历执行；可指定派单子集（留空=预览命中的全部）。 */
@Data
public class ShiftExecuteRequest {
    /** 依据哪一年的生效假期日历（与预览同年） */
    @NotNull(message = "顺延依据的日历年份不能为空")
    private Integer year;

    /** 只顺延这些派单号；留空=本次扫描命中的全部在途派单 */
    private List<String> dispatchNos;

    /** 顺延原因（随批次留底，默认「假期日历调整，休息日顺延」） */
    private String reason;
}
