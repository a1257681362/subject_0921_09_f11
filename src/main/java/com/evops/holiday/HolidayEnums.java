package com.evops.holiday;

/**
 * F11 假期日历与档期自动顺延状态口径。
 *
 * 三张口径：
 * - 日类型（日历条目）：REST 休息日 / WORK 调休上班日——两类分开维护，同日互斥；
 *   版本中没有的日期回退自然周（周一~五工作、周六日休）。
 * - 假期日历状态：DRAFT 编制中（有草稿未发布）/ EFFECTIVE 已发布生效（主档当前版本指针为准）；
 *   版本状态 DRAFT / EFFECTIVE / SUPERSEDED。
 * - 顺延批次/记录状态：批次 PREVIEWED 预览待确认 / CONFIRMED 已批量执行；
 *   逐单记录 PLANNED 待确认 / SHIFTED 已顺延 / SKIPPED 确认时跳过。
 */
public final class HolidayEnums {
    private HolidayEnums() {}

    // ---- 日类型 ----
    /** 休息日：放假（哪怕本来是工作日）；上刊发生日落在此即顺延 */
    public static final String DAY_REST = "REST";
    /** 调休上班日：上班（哪怕本来是周末）；顺延到它即停，不继续往后推 */
    public static final String DAY_WORK = "WORK";

    // ---- 假期日历主档状态 ----
    public static final String CALENDAR_DRAFT = "DRAFT";
    public static final String CALENDAR_EFFECTIVE = "EFFECTIVE";

    // ---- 日历版本状态 ----
    public static final String VERSION_DRAFT = "DRAFT";
    public static final String VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";

    // ---- 版本来源 ----
    /** 按年导入（覆盖发布） */
    public static final String SOURCE_IMPORT = "IMPORT";
    /** 手工整表配置 */
    public static final String SOURCE_MANUAL = "MANUAL";

    // ---- 顺延批次状态 ----
    /** 已出预览，等待运营确认批量执行 */
    public static final String BATCH_PREVIEWED = "PREVIEWED";
    /** 已确认批量执行完毕（逐单结果看记录行 SHIFTED/SKIPPED） */
    public static final String BATCH_CONFIRMED = "CONFIRMED";

    // ---- 顺延逐单记录状态 ----
    public static final String RECORD_PLANNED = "PLANNED";
    public static final String RECORD_SHIFTED = "SHIFTED";
    public static final String RECORD_SKIPPED = "SKIPPED";

    // 安装派单联动结果（install_action）取值定义在安装域 InstallEnums：
    // NONE 无派单 / RESCHEDULED 联动改期通知到队 / TO_PENDING 队冲突转待匹配 / NOT_DONE 已到场完工不联动。

    public static String dayTypeLabel(String s) {
        if (DAY_REST.equals(s)) {
            return "休息日";
        }
        if (DAY_WORK.equals(s)) {
            return "调休上班日";
        }
        return s;
    }

    public static String calendarStatusLabel(String s) {
        if (CALENDAR_DRAFT.equals(s)) {
            return "编制中";
        }
        if (CALENDAR_EFFECTIVE.equals(s)) {
            return "已发布生效";
        }
        return s;
    }

    public static String versionStatusLabel(String s) {
        if (VERSION_DRAFT.equals(s)) {
            return "编制中";
        }
        if (VERSION_EFFECTIVE.equals(s)) {
            return "生效中";
        }
        if (VERSION_SUPERSEDED.equals(s)) {
            return "已被新版取代";
        }
        return s;
    }

    public static String sourceLabel(String s) {
        if (SOURCE_IMPORT.equals(s)) {
            return "按年导入";
        }
        if (SOURCE_MANUAL.equals(s)) {
            return "手工配置";
        }
        return s;
    }

    public static String batchStatusLabel(String s) {
        if (BATCH_PREVIEWED.equals(s)) {
            return "预览待确认";
        }
        if (BATCH_CONFIRMED.equals(s)) {
            return "已批量执行";
        }
        return s;
    }

    public static String recordStatusLabel(String s) {
        if (RECORD_PLANNED.equals(s)) {
            return "待确认";
        }
        if (RECORD_SHIFTED.equals(s)) {
            return "已顺延";
        }
        if (RECORD_SKIPPED.equals(s)) {
            return "已跳过";
        }
        return s;
    }

    /** 安装联动结果中文标签（常量定义在安装域 {@link com.evops.install.InstallEnums}）。 */
    public static String installActionLabel(String s) {
        if (s == null) {
            return "";
        }
        switch (s) {
            case com.evops.install.InstallEnums.INSTALL_NONE: return "无派单";
            case com.evops.install.InstallEnums.INSTALL_RESCHEDULED: return "派单已联动改期并通知到队";
            case com.evops.install.InstallEnums.INSTALL_TO_PENDING: return "队档期冲突，派单转待匹配";
            case com.evops.install.InstallEnums.INSTALL_NOT_DONE: return "派单已到场/完工，未联动";
            default: return s;
        }
    }
}
