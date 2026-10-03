package com.evops.holiday.dto;

import com.evops.holiday.entity.HolidayCalendar;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.entity.HolidayDay;
import lombok.Data;

import java.util.List;

/** 导入结果：草稿版本 + 全量日期 + 与当前生效版的差异比对（确认后再发布覆盖）。 */
@Data
public class ImportResultView {
    private HolidayCalendar calendar;
    private HolidayCalendarVersion version;
    /** 本版全量日期（按日期升序） */
    private List<HolidayDay> days;
    /** 与当前生效版的差异明细（按日期升序）；无生效版时全部为新增 */
    private List<DayDiffItem> diffItems;
    private int addedCount;
    private int removedCount;
    private int changedCount;
    /** 与当前生效版完全一致（导入了个寂寞，提示不必发布） */
    private boolean sameAsEffective;
}
