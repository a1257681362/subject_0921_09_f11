package com.evops.holiday.dto;

import lombok.Data;

import java.time.LocalDate;

/** 导入版本与当前生效版的单日差异行。 */
@Data
public class DayDiffItem {
    /** ADDED 新增 / REMOVED 移除 / CHANGED 类型或名称变更 */
    private String changeType;
    private LocalDate date;
    /** 新日期类型（REMOVED 时为旧类型） */
    private String kind;
    /** 新名称 */
    private String name;
    /** 旧日期类型（ADDED 时为空） */
    private String oldKind;
    /** 旧名称 */
    private String oldName;
}
