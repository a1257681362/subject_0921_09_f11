package com.evops.holiday.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.holiday.HolidayEnums;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.entity.HolidayDay;
import com.evops.holiday.mapper.HolidayCalendarVersionMapper;
import com.evops.holiday.mapper.HolidayDayMapper;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作日判定（顺延引擎共用一处，不散在接口层）：
 * - 日历条目 WORK（调休上班日）→ 工作日，哪怕本来是周末；
 * - 日历条目 REST（休息日）→ 休息日，哪怕本来是工作日；
 * - 该年没有生效版本/日期不在条目里 → 回退自然周（周一~五工作、周六日休）。
 *
 * 实时读当前生效版本条目，不做跨请求缓存（发布后判定即时生效，与受限名单同口径）。
 */
@Component
public class WorkingDayCalendar {

    private final HolidayCalendarVersionMapper versionMapper;
    private final HolidayDayMapper dayMapper;

    public WorkingDayCalendar(HolidayCalendarVersionMapper versionMapper, HolidayDayMapper dayMapper) {
        this.versionMapper = versionMapper;
        this.dayMapper = dayMapper;
    }

    /** 某年当前生效版本；未发布过返回 null。 */
    public HolidayCalendarVersion effectiveVersion(int year) {
        return versionMapper.selectOne(new LambdaQueryWrapper<HolidayCalendarVersion>()
                .eq(HolidayCalendarVersion::getStatus, HolidayEnums.VERSION_EFFECTIVE)
                .apply("calendar_id = (SELECT id FROM t_holiday_calendar WHERE cal_year = {0})", year)
                .orderByDesc(HolidayCalendarVersion::getVersionNo)
                .last("LIMIT 1"));
    }

    /** 生效版条目 日期→条目（无生效版=空映射，全部回退自然周）。 */
    public Map<LocalDate, HolidayDay> effectiveDayMap(int year) {
        HolidayCalendarVersion v = effectiveVersion(year);
        if (v == null) {
            return new LinkedHashMap<>();
        }
        List<HolidayDay> days = dayMapper.selectList(new LambdaQueryWrapper<HolidayDay>()
                .eq(HolidayDay::getVersionId, v.getId())
                .orderByAsc(HolidayDay::getHoliDate));
        Map<LocalDate, HolidayDay> map = new LinkedHashMap<>();
        for (HolidayDay d : days) {
            map.put(d.getHoliDate(), d);
        }
        return map;
    }

    /**
     * 某日命中的日历条目；无生效版或该日无条目返回 null（调用方回退自然周）。
     */
    public HolidayDay findEntry(LocalDate date) {
        HolidayCalendarVersion v = effectiveVersion(date.getYear());
        if (v == null) {
            return null;
        }
        return dayMapper.selectOne(new LambdaQueryWrapper<HolidayDay>()
                .eq(HolidayDay::getVersionId, v.getId())
                .eq(HolidayDay::getHoliDate, date)
                .last("LIMIT 1"));
    }

    /** 是否工作日：WORK 条目/普通周一~五为是；REST 条目/普通周末为否。 */
    public boolean isWorking(LocalDate date) {
        HolidayDay entry = findEntry(date);
        if (entry != null) {
            return HolidayEnums.DAY_WORK.equals(entry.getDayType());
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /**
     * 从 from 的次日起逐日找第一个工作日（休息日才会调它，from 本身不计）。
     * 调休上班日是工作日，顺延到它当天即停；找不到（超过 maxScanDays）抛 IllegalArgumentException 配置问题由调用方转业务异常。
     */
    public LocalDate nextWorkingDay(LocalDate from, int maxScanDays) {
        LocalDate cursor = from.plusDays(1);
        for (int i = 0; i < maxScanDays; i++) {
            if (isWorking(cursor)) {
                return cursor;
            }
            cursor = cursor.plusDays(1);
        }
        throw new IllegalArgumentException("自 " + from + " 起 " + maxScanDays
                + " 天内没有工作日，请检查假期日历休息日配置是否异常");
    }
}
