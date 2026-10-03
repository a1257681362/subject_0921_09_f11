package com.evops.holiday.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.holiday.HolidayEnums;
import com.evops.holiday.dto.HolidayDaySpec;
import com.evops.holiday.dto.HolidayImportDiffView;
import com.evops.holiday.dto.WorkingDayView;
import com.evops.holiday.entity.HolidayCalendar;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.entity.HolidayDay;
import com.evops.holiday.mapper.HolidayCalendarMapper;
import com.evops.holiday.mapper.HolidayCalendarVersionMapper;
import com.evops.holiday.mapper.HolidayDayMapper;
import com.evops.holiday.support.WorkingDayCalendar;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 假期日历域：年度主档 → 版本（草稿/导入发布即生效/旧版取代）→ 条目（REST 休息日 / WORK 调休上班日）。
 *
 * 口径：
 * - 休息日与调休上班日分开维护，同日两类互斥；未发布年历的日期回退自然周；
 * - 按年导入先 /import/preview 出差异（新增/删除/类型变化/不变），确认后整表替换发布新版，旧版自动取代；
 * - 版本链只追加，经手人/发布时刻/导入说明/条目数随版留底，版本链即操作记录；
 * - 工作日判定实时读生效版（见 WorkingDayCalendar），无缓存。
 */
@Service
public class HolidayCalendarService {

    private final HolidayCalendarMapper calendarMapper;
    private final HolidayCalendarVersionMapper versionMapper;
    private final HolidayDayMapper dayMapper;
    private final WorkingDayCalendar workingDayCalendar;

    public HolidayCalendarService(HolidayCalendarMapper calendarMapper,
                                  HolidayCalendarVersionMapper versionMapper,
                                  HolidayDayMapper dayMapper,
                                  WorkingDayCalendar workingDayCalendar) {
        this.calendarMapper = calendarMapper;
        this.versionMapper = versionMapper;
        this.dayMapper = dayMapper;
        this.workingDayCalendar = workingDayCalendar;
    }

    // ---- 查询 ----

    /** 按年取主档；该年尚未建档返回 null。 */
    public HolidayCalendar findByYear(int year) {
        return calendarMapper.selectOne(new LambdaQueryWrapper<HolidayCalendar>()
                .eq(HolidayCalendar::getCalYear, year));
    }

    public HolidayCalendar requireCalendar(int year) {
        HolidayCalendar cal = findByYear(year);
        if (cal == null) {
            throw new BusinessException(year + " 年假期日历尚未建档，请先按年导入或另起草稿");
        }
        return cal;
    }

    /** 某年当前生效版本；无生效版返回 null。 */
    public HolidayCalendarVersion currentEffective(int year) {
        return workingDayCalendar.effectiveVersion(year);
    }

    public List<HolidayCalendarVersion> listVersions(int year) {
        HolidayCalendar cal = findByYear(year);
        if (cal == null) {
            return Collections.emptyList();
        }
        return versionMapper.selectList(new LambdaQueryWrapper<HolidayCalendarVersion>()
                .eq(HolidayCalendarVersion::getCalendarId, cal.getId())
                .orderByDesc(HolidayCalendarVersion::getVersionNo));
    }

    public HolidayCalendarVersion requireVersion(Long versionId) {
        HolidayCalendarVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new BusinessException("假期日历版本不存在: id=" + versionId);
        }
        return v;
    }

    public List<HolidayDay> itemsOf(Long versionId) {
        return dayMapper.selectList(new LambdaQueryWrapper<HolidayDay>()
                .eq(HolidayDay::getVersionId, versionId)
                .orderByAsc(HolidayDay::getHoliDate));
    }

    // ---- 手工草稿：另起 → 整表配置 → 发布 ----

    /** 另起草稿：年历缺档则先建 DRAFT 主档；从当前生效版拷贝条目；已有草稿幂等返回。 */
    @Transactional(rollbackFor = Exception.class)
    public HolidayCalendarVersion newDraft(int year, Viewer viewer) {
        viewer.requireCalendarAdmin();
        validateYear(year);
        HolidayCalendar cal = findByYear(year);
        if (cal == null) {
            cal = createCalendar(year, viewer.getUsername(), null);
        }
        HolidayCalendarVersion draft = versionMapper.selectOne(new LambdaQueryWrapper<HolidayCalendarVersion>()
                .eq(HolidayCalendarVersion::getCalendarId, cal.getId())
                .eq(HolidayCalendarVersion::getStatus, HolidayEnums.VERSION_DRAFT)
                .last("LIMIT 1"));
        if (draft != null) {
            return draft;
        }
        HolidayCalendarVersion effective = currentEffective(year);
        return createDraftFrom(cal, effective, HolidayEnums.SOURCE_MANUAL, viewer.getUsername());
    }

    /** 整表配置草稿条目：日期必须属于该年，REST/WORK 合法且同日不重复。 */
    @Transactional(rollbackFor = Exception.class)
    public HolidayCalendarVersion configure(Long versionId, List<HolidayDaySpec> days, Viewer viewer) {
        viewer.requireCalendarAdmin();
        HolidayCalendarVersion v = requireVersion(versionId);
        requireDraft(v, "配置条目");
        HolidayCalendar cal = calendarMapper.selectById(v.getCalendarId());
        replaceItems(v, cal.getCalYear(), days);
        return v;
    }

    /** 发布手工草稿：旧生效版置 SUPERSEDED，主档指针更新为 EFFECTIVE。 */
    @Transactional(rollbackFor = Exception.class)
    public HolidayCalendarVersion publish(Long versionId, String changeSummary, Viewer viewer) {
        viewer.requireCalendarAdmin();
        HolidayCalendarVersion v = requireVersion(versionId);
        requireDraft(v, "发布");
        HolidayCalendar cal = calendarMapper.selectById(v.getCalendarId());
        doPublish(cal, v, changeSummary, viewer.getUsername());
        return v;
    }

    // ---- 按年导入：先比差异，确认整表替换发布 ----

    /** 差异比对（不落库）：相对当前生效版算新增/删除/类型变化/不变。 */
    public HolidayImportDiffView importPreview(int year, List<HolidayDaySpec> specs) {
        validateYear(year);
        List<HolidayDay> incoming = normalizeAndValidate(year, specs);
        Map<LocalDate, HolidayDay> oldMap = new LinkedHashMap<>();
        HolidayCalendarVersion old = currentEffective(year);
        if (old != null) {
            for (HolidayDay d : itemsOf(old.getId())) {
                oldMap.put(d.getHoliDate(), d);
            }
        }
        Map<LocalDate, HolidayDay> newMap = new LinkedHashMap<>();
        for (HolidayDay d : incoming) {
            newMap.put(d.getHoliDate(), d);
        }

        HolidayImportDiffView view = new HolidayImportDiffView();
        view.setCalYear(year);
        view.setOldVersionNo(old == null ? null : old.getVersionNo());
        int added = 0, removed = 0, changed = 0, unchanged = 0, rest = 0, work = 0;
        List<HolidayImportDiffView.DiffDay> addedList = new ArrayList<>();
        List<HolidayImportDiffView.DiffDay> removedList = new ArrayList<>();
        List<HolidayImportDiffView.DiffDay> changedList = new ArrayList<>();
        List<HolidayImportDiffView.DiffDay> unchangedList = new ArrayList<>();

        for (HolidayDay d : incoming) {
            HolidayDay o = oldMap.get(d.getHoliDate());
            HolidayImportDiffView.DiffDay row = HolidayImportDiffView.DiffDay.of(d.getHoliDate(), d.getDayType(), d.getHolidayName());
            if (o == null) {
                added++;
                addedList.add(row);
            } else if (!o.getDayType().equals(d.getDayType())) {
                changed++;
                row.setOldType(o.getDayType());
                row.setOldName(o.getHolidayName());
                changedList.add(row);
            } else {
                unchanged++;
                unchangedList.add(row);
            }
            if (HolidayEnums.DAY_REST.equals(d.getDayType())) {
                rest++;
            } else {
                work++;
            }
        }
        for (HolidayDay o : oldMap.values()) {
            if (!newMap.containsKey(o.getHoliDate())) {
                removed++;
                HolidayImportDiffView.DiffDay row = HolidayImportDiffView.DiffDay.of(o.getHoliDate(), o.getDayType(), o.getHolidayName());
                row.setOldType(o.getDayType());
                row.setOldName(o.getHolidayName());
                removedList.add(row);
            }
        }
        view.setAddedCount(added);
        view.setRemovedCount(removed);
        view.setTypeChangedCount(changed);
        view.setUnchangedCount(unchanged);
        view.setNewRestCount(rest);
        view.setNewWorkCount(work);
        view.setAdded(addedList);
        view.setRemoved(removedList);
        view.setTypeChanged(changedList);
        view.setUnchanged(unchangedList);
        return view;
    }

    /**
     * 确认按年导入：整表替换并直接发布新版（首次导入即生效，无空窗）；
     * 旧生效版与任何编制中草稿一并置 SUPERSEDED（草稿没发布就被整表导入覆盖，不留半截口径）。
     */
    @Transactional(rollbackFor = Exception.class)
    public HolidayCalendarVersion importConfirm(int year, List<HolidayDaySpec> specs,
                                               String changeSummary, Viewer viewer) {
        viewer.requireCalendarAdmin();
        validateYear(year);
        List<HolidayDay> incoming = normalizeAndValidate(year, specs);
        HolidayCalendar cal = findByYear(year);
        if (cal == null) {
            cal = createCalendar(year, viewer.getUsername(), changeSummary);
        }
        HolidayCalendarVersion v = new HolidayCalendarVersion();
        v.setCalendarId(cal.getId());
        v.setVersionNo(nextVersionNo(cal.getId()));
        v.setStatus(HolidayEnums.VERSION_DRAFT);
        v.setSource(HolidayEnums.SOURCE_IMPORT);
        v.setOperatorName(viewer.getUsername());
        versionMapper.insert(v);
        insertDays(v, year, incoming);

        // 旧生效版与未发布草稿都让位给导入版
        List<HolidayCalendarVersion> olds = versionMapper.selectList(new LambdaQueryWrapper<HolidayCalendarVersion>()
                .eq(HolidayCalendarVersion::getCalendarId, cal.getId())
                .ne(HolidayCalendarVersion::getId, v.getId())
                .in(HolidayCalendarVersion::getStatus,
                        HolidayEnums.VERSION_EFFECTIVE, HolidayEnums.VERSION_DRAFT));
        for (HolidayCalendarVersion o : olds) {
            HolidayCalendarVersion sup = new HolidayCalendarVersion();
            sup.setId(o.getId());
            sup.setStatus(HolidayEnums.VERSION_SUPERSEDED);
            versionMapper.updateById(sup);
        }
        doPublish(cal, v, changeSummary, viewer.getUsername());
        return v;
    }

    // ---- 工作日判定 ----

    public WorkingDayView inspect(LocalDate date) {
        if (date == null) {
            throw new BusinessException("日期不能为空");
        }
        WorkingDayView view = new WorkingDayView();
        view.setDate(date);
        HolidayDay entry = workingDayCalendar.findEntry(date);
        HolidayCalendarVersion effective = workingDayCalendar.effectiveVersion(date.getYear());
        view.setCalendarVersionNo(effective == null ? null : effective.getVersionNo());
        DayOfWeek dow = date.getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        if (entry != null) {
            view.setWorking(HolidayEnums.DAY_WORK.equals(entry.getDayType()));
            view.setBasis(entry.getDayType());
            view.setBasisLabel(HolidayEnums.dayTypeLabel(entry.getDayType()));
            view.setHolidayName(entry.getHolidayName());
        } else if (weekend) {
            view.setWorking(false);
            view.setBasis("WEEKEND");
            view.setBasisLabel("普通周末");
        } else {
            view.setWorking(true);
            view.setBasis("WEEKDAY");
            view.setBasisLabel("普通工作日");
        }
        return view;
    }

    // ---- 内部 ----

    private HolidayCalendar createCalendar(int year, String operator, String remark) {
        HolidayCalendar cal = new HolidayCalendar();
        cal.setCalendarNo(generateCalendarNo());
        cal.setCalYear(year);
        cal.setCalendarName(year + "年度假期日历");
        cal.setStatus(HolidayEnums.CALENDAR_DRAFT);
        cal.setOperatorName(operator);
        cal.setRemark(trimTo(remark, 255));
        try {
            calendarMapper.insert(cal);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(year + " 年假期日历刚被创建，请刷新重试");
        }
        return cal;
    }

    private HolidayCalendarVersion createDraftFrom(HolidayCalendar cal, HolidayCalendarVersion effective,
                                                   String source, String operator) {
        HolidayCalendarVersion v = new HolidayCalendarVersion();
        v.setCalendarId(cal.getId());
        v.setVersionNo(nextVersionNo(cal.getId()));
        v.setStatus(HolidayEnums.VERSION_DRAFT);
        v.setSource(source);
        v.setOperatorName(operator);
        versionMapper.insert(v);
        if (effective != null) {
            List<HolidayDay> copies = new ArrayList<>();
            for (HolidayDay d : itemsOf(effective.getId())) {
                HolidayDay copy = new HolidayDay();
                copy.setVersionId(v.getId());
                copy.setCalYear(cal.getCalYear());
                copy.setHoliDate(d.getHoliDate());
                copy.setDayType(d.getDayType());
                copy.setHolidayName(d.getHolidayName());
                copies.add(copy);
            }
            insertDays(v, cal.getCalYear(), copies);
        }
        return v;
    }

    private void doPublish(HolidayCalendar cal, HolidayCalendarVersion v, String summary, String operator) {
        HolidayCalendarVersion old = currentEffective(cal.getCalYear());
        if (old != null && !old.getId().equals(v.getId())) {
            HolidayCalendarVersion sup = new HolidayCalendarVersion();
            sup.setId(old.getId());
            sup.setStatus(HolidayEnums.VERSION_SUPERSEDED);
            versionMapper.updateById(sup);
        }
        LocalDateTime now = LocalDateTime.now();
        HolidayCalendarVersion patch = new HolidayCalendarVersion();
        patch.setId(v.getId());
        patch.setStatus(HolidayEnums.VERSION_EFFECTIVE);
        patch.setPublishTime(now);
        patch.setOperatorName(operator);
        patch.setChangeSummary(trimTo(summary == null || summary.trim().isEmpty() ? "假期日历发布" : summary.trim(), 255));
        versionMapper.updateById(patch);
        v.setStatus(HolidayEnums.VERSION_EFFECTIVE);
        v.setPublishTime(now);
        v.setChangeSummary(patch.getChangeSummary());

        HolidayCalendar calPatch = new HolidayCalendar();
        calPatch.setId(cal.getId());
        calPatch.setStatus(HolidayEnums.CALENDAR_EFFECTIVE);
        calPatch.setCurrentVersionId(v.getId());
        calPatch.setCurrentVersionNo(v.getVersionNo());
        calPatch.setOperatorName(operator);
        calendarMapper.updateById(calPatch);
    }

    /** 整表替换版本条目（先清后插），并回写版本 REST/WORK 计数。 */
    private void replaceItems(HolidayCalendarVersion v, int year, List<HolidayDaySpec> specs) {
        List<HolidayDay> days = normalizeAndValidate(year, specs);
        dayMapper.delete(new LambdaQueryWrapper<HolidayDay>().eq(HolidayDay::getVersionId, v.getId()));
        insertDays(v, year, days);
    }

    private void insertDays(HolidayCalendarVersion v, int year, List<HolidayDay> days) {
        int rest = 0, work = 0;
        for (HolidayDay d : days) {
            d.setId(null);
            d.setVersionId(v.getId());
            d.setCalYear(year);
            try {
                dayMapper.insert(d);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("日期 " + d.getHoliDate() + " 在版本 v" + v.getVersionNo() + " 重复配置");
            }
            if (HolidayEnums.DAY_REST.equals(d.getDayType())) {
                rest++;
            } else {
                work++;
            }
        }
        HolidayCalendarVersion patch = new HolidayCalendarVersion();
        patch.setId(v.getId());
        patch.setRestCount(rest);
        patch.setWorkCount(work);
        versionMapper.updateById(patch);
        v.setRestCount(rest);
        v.setWorkCount(work);
    }

    /** 归一 + 校验：类型合法、日期属于该年、同日不重复。返回可直接插库的条目（无 id/version）。 */
    private List<HolidayDay> normalizeAndValidate(int year, List<HolidayDaySpec> specs) {
        if (specs == null) {
            throw new BusinessException("日历条目不能为空");
        }
        Set<LocalDate> seen = new LinkedHashSet<>();
        List<HolidayDay> days = new ArrayList<>();
        for (HolidayDaySpec spec : specs) {
            if (spec == null || spec.getDate() == null) {
                throw new BusinessException("存在日期为空的日历条目");
            }
            LocalDate date = spec.getDate();
            if (date.getYear() != year) {
                throw new BusinessException("日期 " + date + " 不属于 " + year + " 年，按年导入/配置只能放该年日期");
            }
            String type = spec.getType() == null ? "" : spec.getType().trim().toUpperCase();
            if (!HolidayEnums.DAY_REST.equals(type) && !HolidayEnums.DAY_WORK.equals(type)) {
                throw new BusinessException("日期 " + date + " 的类型非法：" + spec.getType()
                        + "（只接受 REST 休息日 / WORK 调休上班日）");
            }
            if (!seen.add(date)) {
                throw new BusinessException("日期 " + date + " 重复配置，同一天只能是休息日或调休上班日之一");
            }
            HolidayDay d = new HolidayDay();
            d.setHoliDate(date);
            d.setDayType(type);
            d.setHolidayName(trimTo(spec.getName(), 64));
            days.add(d);
        }
        return days;
    }

    private void requireDraft(HolidayCalendarVersion v, String action) {
        if (!HolidayEnums.VERSION_DRAFT.equals(v.getStatus())) {
            throw new BusinessException("假期日历版本 v" + v.getVersionNo() + " 已"
                    + HolidayEnums.versionStatusLabel(v.getStatus()) + "，不能" + action
                    + "；调整请另起新版或按年导入");
        }
    }

    private int nextVersionNo(Long calendarId) {
        HolidayCalendarVersion max = versionMapper.selectOne(new LambdaQueryWrapper<HolidayCalendarVersion>()
                .eq(HolidayCalendarVersion::getCalendarId, calendarId)
                .orderByDesc(HolidayCalendarVersion::getVersionNo)
                .last("LIMIT 1"));
        return max == null ? 1 : max.getVersionNo() + 1;
    }

    private void validateYear(int year) {
        if (year < 2000 || year > 2100) {
            throw new BusinessException("年份非法: " + year);
        }
    }

    private String generateCalendarNo() {
        return String.format("HC%010d", calendarMapper.nextCalendarSeq());
    }

    private String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
