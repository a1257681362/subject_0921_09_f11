package com.evops.holiday;

/**
 * F11 假期日历与安装派单顺延状态口径。
 *
 * 三张口径：
 * - 假期日历状态（主档）：DRAFT 编制中 / EFFECTIVE 生效中；
 * - 假期日历版本：DRAFT 编制中 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代；
 *   按年导入即起草稿（已有草稿整表覆盖），发布生效、旧版自动取代，版本链即操作记录；
 * - 顺延记录状态：SHIFTED 已顺延 / FAILED 顺延失败（队档期冲突等，单留原档期人工改期）。
 *
 * 日期类型分开维护：REST 休息日 / MAKEUP_WORK 调休上班日；同一版本内一个日期只能落一边。
 * 顺延口径：只挡显式维护的休息日（普通周末照常派单），调休上班日是工作日、不动。
 */
public final class HolidayEnums {
    private HolidayEnums() {}

    // ---- 假期日历状态（主档） ----
    /** 编制中：已导入草稿，尚未发布生效版 */
    public static final String CAL_DRAFT = "DRAFT";
    /** 生效中：已有生效版本，顺延以它为据 */
    public static final String CAL_EFFECTIVE = "EFFECTIVE";

    // ---- 假期日历版本状态 ----
    public static final String VERSION_DRAFT = "DRAFT";
    public static final String VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";

    // ---- 日期类型（休息日/调休上班日分开维护） ----
    /** 休息日：法定假日/调休放假日；计划落在上面的在途派单要顺延 */
    public static final String KIND_REST = "REST";
    /** 调休上班日：周末调为上班；是工作日，计划落在上面不动 */
    public static final String KIND_MAKEUP_WORK = "MAKEUP_WORK";

    // ---- 顺延记录状态 ----
    /** 已顺延：计划已挪到下一个工作日，原档期留痕在本记录与事件里 */
    public static final String RECORD_SHIFTED = "SHIFTED";
    /** 顺延失败：队档期冲突等原因未硬挪，单留原档期，人工改期 */
    public static final String RECORD_FAILED = "FAILED";

    public static boolean isRest(String kind) {
        return KIND_REST.equals(kind);
    }

    public static boolean isMakeupWork(String kind) {
        return KIND_MAKEUP_WORK.equals(kind);
    }

    public static String statusLabel(String s) {
        if (s == null) {
            return "";
        }
        switch (s) {
            case CAL_DRAFT: return "编制中";
            case CAL_EFFECTIVE: return "生效中";
            default: return s;
        }
    }

    public static String versionStatusLabel(String s) {
        if (s == null) {
            return "";
        }
        switch (s) {
            case VERSION_DRAFT: return "编制中";
            case VERSION_EFFECTIVE: return "生效中";
            case VERSION_SUPERSEDED: return "已被新版取代";
            default: return s;
        }
    }

    public static String dayKindLabel(String kind) {
        if (isRest(kind)) {
            return "休息日";
        }
        if (isMakeupWork(kind)) {
            return "调休上班日";
        }
        return kind == null ? "" : kind;
    }

    public static String recordStatusLabel(String s) {
        if (s == null) {
            return "";
        }
        switch (s) {
            case RECORD_SHIFTED: return "已顺延";
            case RECORD_FAILED: return "顺延失败";
            default: return s;
        }
    }
}
