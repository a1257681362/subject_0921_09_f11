package com.evops.holiday.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 按年导入差异比对（覆盖前先比对，不落库）：
 * ADDED 新增 / REMOVED 删除 / TYPE_CHANGED REST⇄WORK 类型变化 / UNCHANGED 无变化。
 */
@Data
public class HolidayImportDiffView {
    private Integer calYear;
    /** 比对所对的旧生效版本号；首次导入为 null */
    private Integer oldVersionNo;
    private Integer addedCount;
    private Integer removedCount;
    private Integer typeChangedCount;
    private Integer unchangedCount;
    /** 导入后将生效的休息日/上班日条数 */
    private Integer newRestCount;
    private Integer newWorkCount;

    private List<DiffDay> added = new ArrayList<>();
    private List<DiffDay> removed = new ArrayList<>();
    private List<DiffDay> typeChanged = new ArrayList<>();
    private List<DiffDay> unchanged = new ArrayList<>();

    @Data
    public static class DiffDay {
        private LocalDate date;
        /** 导入后的类型（REMOVED 行为旧类型） */
        private String type;
        private String name;
        /** 仅 TYPE_CHANGED/REMOVED 用：旧类型 */
        private String oldType;
        private String oldName;

        public static DiffDay of(LocalDate date, String type, String name) {
            DiffDay d = new DiffDay();
            d.date = date;
            d.type = type;
            d.name = name;
            return d;
        }
    }
}
