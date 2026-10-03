package com.evops.holiday.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.holiday.HolidayEnums;
import com.evops.holiday.dto.CalendarImportRequest;
import com.evops.holiday.dto.DayDiffItem;
import com.evops.holiday.dto.DaySpec;
import com.evops.holiday.dto.ImportResultView;
import com.evops.holiday.entity.HolidayCalendar;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.entity.HolidayDay;
import com.evops.holiday.mapper.HolidayCalendarMapper;
import com.evops.holiday.mapper.HolidayCalendarVersionMapper;
import com.evops.holiday.mapper.HolidayDayMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 假期日历域：主档（一个自然年一份）→ 版本（导入即草稿/发布生效/旧版取代）→ 日期行（休息日/调休上班日）。
 *
 * 口径：
 * - 休息日 REST 与调休上班日 MAKEUP_WORK 分开维护，同一版本内一个日期只能落一边；
 * - 日历按年导入（整表替换式）：该年已有编制中草稿则整表覆盖草稿，否则另起新草稿版；
 *   导入即返回与当前生效版的差异比对（新增/移除/变更），确认后再发布覆盖生效版；
 * - 发布即时生效（顺延口径实时读生效版，无缓存），旧生效版自动 SUPERSEDED，版本链即操作记录。
 */
@Service
public class HolidayCalendarService {

    private final HolidayCalendarMapper calendarMapper;
    private final HolidayCalendarVersionMapper versionMapper;
    private final HolidayDayMapper dayMapper;

    public HolidayCalendarService(HolidayCalendarMapper calendarMapper,
                                  HolidayCalendarVersionMapper versionMapper,
                                  HolidayDayMapper dayMapper) {
        this.calendarMapper = calendarMapper;
        this.versionMapper = versionMapper;
        this.dayMapper = dayMapper;
    }

    // ---- 查询 ----

    public List<HolidayCalendar> list(Integer year) {
        LambdaQueryWrapper<HolidayCalendar> wrapper = new LambdaQueryWrapper<>();
        if (year != null) {
            wrapper.eq(HolidayCalendar::getCalYear, year);
        }
        wrapper.orderByDesc(HolidayCalendar::getCalYear);
        return calendarMapper.selectList(wrapper);
    }

    public HolidayCalendar requireCalendar(long id) {
        HolidayCalendar calendar = calendarMapper.selectById(id);
        if (calendar == null) {
            throw new BusinessException("假期日历不存在: id=" + id);
        }
        return calendar;
    }

    /** 某年的日历；没有返回 null。 */
    public HolidayCalendar findByYear(int year) {
        return calendarMapper.selectOne(new LambdaQueryWrapper<HolidayCalendar>()
                .eq(HolidayCalendar::getCalYear, year));
    }

    public List<HolidayCalendarVersion> versionsOf(long calendarId) {
        return versionMapper.selectList(new LambdaQueryWrapper<HolidayCalendarVersion>()
                .eq(HolidayCalendarVersion::getCalendarId, calendarId)
                .orderByDesc(HolidayCalendarVersion::getVersionNo));
    }

    public HolidayCalendarVersion requireVersion(long versionId) {
        HolidayCalendarVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new BusinessException("假期日历版本不存在: id=" + versionId);
        }
        return v;
    }

    /** 当前生效版本（该年还没有生效版返回 null = 没有顺延依据）。 */
    public HolidayCalendarVersion currentEffective(long calendarId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<HolidayCalendarVersion>()
                .eq(HolidayCalendarVersion::getCalendarId, calendarId)
                .eq(HolidayCalendarVersion::getStatus, HolidayEnums.VERSION_EFFECTIVE)
                .orderByDesc(HolidayCalendarVersion::getVersionNo)
                .last("LIMIT 1"));
    }

    public List<HolidayDay> daysOf(long versionId) {
        return dayMapper.selectList(new LambdaQueryWrapper<HolidayDay>()
                .eq(HolidayDay::getVersionId, versionId)
                .orderByAsc(HolidayDay::getDayDate));
    }

    /** 生效版休息日期集合（顺延口径只挡显式维护的休息日；调休上班日是工作日）。 */
    public java.util.Set<LocalDate> effectiveRestDates(long calendarId) {
        HolidayCalendarVersion effective = currentEffective(calendarId);
        if (effective == null) {
            return new java.util.LinkedHashSet<>();
        }
        return dayMapper.selectList(new LambdaQueryWrapper<HolidayDay>()
                        .eq(HolidayDay::getVersionId, effective.getId())
                        .eq(HolidayDay::getDayKind, HolidayEnums.KIND_REST))
                .stream().map(HolidayDay::getDayDate)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    // ---- 导入（按年整表导入，覆盖前先比对差异） ----

    /**
     * 按年导入：该年没有日历则建主档；已有编制中草稿则整表覆盖草稿日期，否则另起新草稿版。
     * 导入不直接生效——返回草稿版本与当前生效版的差异比对，确认后走发布覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResultView importCalendar(CalendarImportRequest request, Viewer viewer) {
        viewer.requireSystemAdmin();
        int year = request.getYear();
        if (year < 2000 || year > 2100) {
            throw new BusinessException("导入年份应在 2000~2100 之间: " + year);
        }
        List<DaySpec> specs = request.getDays() == null ? new ArrayList<>() : request.getDays();
        if (specs.isEmpty()) {
            throw new BusinessException("日期列表不能为空：休息日/调休上班日至少导入一行");
        }
        List<HolidayDay> parsed = validateAndParse(year, specs);

        HolidayCalendar calendar = findByYear(year);
        if (calendar == null) {
            calendar = new HolidayCalendar();
            calendar.setCalendarNo(generateNo());
            calendar.setCalYear(year);
            calendar.setCalendarName(year + " 年假期日历");
            calendar.setStatus(HolidayEnums.CAL_DRAFT);
            calendar.setOperatorName(viewer.getUsername());
            try {
                calendarMapper.insert(calendar);
            } catch (DuplicateKeyException e) {
                // 并发同年导入：主档已建，重取继续
                calendar = findByYear(year);
            }
        }

        HolidayCalendarVersion draft = versionMapper.selectOne(new LambdaQueryWrapper<HolidayCalendarVersion>()
                .eq(HolidayCalendarVersion::getCalendarId, calendar.getId())
                .eq(HolidayCalendarVersion::getStatus, HolidayEnums.VERSION_DRAFT)
                .last("LIMIT 1"));
        if (draft == null) {
            draft = new HolidayCalendarVersion();
            draft.setCalendarId(calendar.getId());
            draft.setVersionNo(nextVersionNo(calendar.getId()));
            draft.setStatus(HolidayEnums.VERSION_DRAFT);
            draft.setRestCount(0);
            draft.setMakeupCount(0);
            draft.setOperatorName(viewer.getUsername());
            try {
                versionMapper.insert(draft);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(year + " 年假期日历正在并发导入，请重试");
            }
        }

        // 整表覆盖草稿日期（与 F10 名单整表配置同口径）
        dayMapper.delete(new LambdaQueryWrapper<HolidayDay>()
                .eq(HolidayDay::getVersionId, draft.getId()));
        int rest = 0;
        int makeup = 0;
        for (HolidayDay day : parsed) {
            day.setVersionId(draft.getId());
            dayMapper.insert(day);
            if (HolidayEnums.isRest(day.getDayKind())) {
                rest++;
            } else {
                makeup++;
            }
        }
        HolidayCalendarVersion patch = new HolidayCalendarVersion();
        patch.setId(draft.getId());
        patch.setRestCount(rest);
        patch.setMakeupCount(makeup);
        patch.setOperatorName(viewer.getUsername());
        patch.setChangeSummary(request.getChangeSummary() == null || request.getChangeSummary().trim().isEmpty()
                ? year + " 年假期日历导入" : request.getChangeSummary().trim());
        patch.setRemark(request.getRemark() == null || request.getRemark().trim().isEmpty()
                ? null : request.getRemark().trim());
        versionMapper.updateById(patch);
        draft.setRestCount(rest);
        draft.setMakeupCount(makeup);
        draft.setChangeSummary(patch.getChangeSummary());
        draft.setRemark(patch.getRemark());

        return buildImportResult(calendar, draft);
    }

    /** 版本详情（含全量日期 + 与当前生效版的差异比对）。 */
    public ImportResultView versionDetail(long versionId) {
        HolidayCalendarVersion v = requireVersion(versionId);
        HolidayCalendar calendar = requireCalendar(v.getCalendarId());
        return buildImportResult(calendar, v);
    }

    // ---- 发布（确认后覆盖生效版） ----

    /** 发布：即时生效，旧生效版自动 SUPERSEDED；主档指针与状态随发布更新。 */
    @Transactional(rollbackFor = Exception.class)
    public HolidayCalendarVersion publish(long versionId, Viewer viewer) {
        viewer.requireSystemAdmin();
        HolidayCalendarVersion v = requireVersion(versionId);
        if (!HolidayEnums.VERSION_DRAFT.equals(v.getStatus())) {
            throw new BusinessException("假期日历版本 v" + v.getVersionNo() + " 已"
                    + HolidayEnums.versionStatusLabel(v.getStatus()) + "，不能发布；调整请重新导入另起新版");
        }
        HolidayCalendar calendar = requireCalendar(v.getCalendarId());
        HolidayCalendarVersion old = currentEffective(calendar.getId());
        if (old != null) {
            HolidayCalendarVersion supersede = new HolidayCalendarVersion();
            supersede.setId(old.getId());
            supersede.setStatus(HolidayEnums.VERSION_SUPERSEDED);
            versionMapper.updateById(supersede);
        }

        LocalDateTime publishTime = LocalDateTime.now();
        HolidayCalendarVersion patch = new HolidayCalendarVersion();
        patch.setId(v.getId());
        patch.setStatus(HolidayEnums.VERSION_EFFECTIVE);
        patch.setPublishTime(publishTime);
        patch.setOperatorName(viewer.getUsername());
        versionMapper.updateById(patch);

        HolidayCalendar calendarPatch = new HolidayCalendar();
        calendarPatch.setId(calendar.getId());
        calendarPatch.setStatus(HolidayEnums.CAL_EFFECTIVE);
        calendarPatch.setCurrentVersionId(v.getId());
        calendarPatch.setCurrentVersionNo(v.getVersionNo());
        calendarPatch.setOperatorName(viewer.getUsername());
        calendarMapper.updateById(calendarPatch);

        v.setStatus(HolidayEnums.VERSION_EFFECTIVE);
        v.setPublishTime(publishTime);
        v.setOperatorName(viewer.getUsername());
        return v;
    }

    // ---- 内部 ----

    /** 导入校验：日期落在导入年份内、类型合法、同一日期不重复（休息日/调休上班日只能落一边）。 */
    private List<HolidayDay> validateAndParse(int year, List<DaySpec> specs) {
        Map<LocalDate, String> seen = new LinkedHashMap<>();
        List<HolidayDay> days = new ArrayList<>();
        for (DaySpec spec : specs) {
            LocalDate date = spec.getDate();
            if (date == null) {
                throw new BusinessException("日期不能为空");
            }
            if (date.getYear() != year) {
                throw new BusinessException("日期 " + date + " 不在导入年份 " + year + " 内，按年导入请拆到对应年份");
            }
            String kind = spec.getKind() == null ? "" : spec.getKind().trim().toUpperCase();
            if (!HolidayEnums.isRest(kind) && !HolidayEnums.isMakeupWork(kind)) {
                throw new BusinessException("日期 " + date + " 的类型「" + spec.getKind()
                        + "」不认识：只支持 REST 休息日 / MAKEUP_WORK 调休上班日");
            }
            String previous = seen.putIfAbsent(date, kind);
            if (previous != null) {
                throw new BusinessException("日期 " + date + " 重复导入（" + HolidayEnums.dayKindLabel(previous)
                        + " 与 " + HolidayEnums.dayKindLabel(kind) + "），同一天只能落一边");
            }
            HolidayDay day = new HolidayDay();
            day.setDayDate(date);
            day.setDayKind(kind);
            day.setDayName(spec.getName() == null || spec.getName().trim().isEmpty()
                    ? null : spec.getName().trim());
            days.add(day);
        }
        days.sort(Comparator.comparing(HolidayDay::getDayDate));
        return days;
    }

    /** 差异比对：导入/查看版本时，与当前生效版逐日比对（新增/移除/类型或名称变更）。 */
    private ImportResultView buildImportResult(HolidayCalendar calendar, HolidayCalendarVersion version) {
        List<HolidayDay> newDays = daysOf(version.getId());
        HolidayCalendarVersion effective = currentEffective(calendar.getId());
        // 生效版就是本版时，差异相对上一版没有意义——与自己比全同
        Map<LocalDate, HolidayDay> oldMap = (effective == null || effective.getId().equals(version.getId()))
                ? new LinkedHashMap<>()
                : daysOf(effective.getId()).stream()
                .collect(Collectors.toMap(HolidayDay::getDayDate, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<LocalDate, HolidayDay> newMap = newDays.stream()
                .collect(Collectors.toMap(HolidayDay::getDayDate, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<DayDiffItem> items = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int changed = 0;
        for (HolidayDay day : newDays) {
            HolidayDay old = oldMap.get(day.getDayDate());
            if (old == null) {
                DayDiffItem item = new DayDiffItem();
                item.setChangeType("ADDED");
                item.setDate(day.getDayDate());
                item.setKind(day.getDayKind());
                item.setName(day.getDayName());
                items.add(item);
                added++;
            } else if (!old.getDayKind().equals(day.getDayKind())
                    || !String.valueOf(old.getDayName()).equals(String.valueOf(day.getDayName()))) {
                DayDiffItem item = new DayDiffItem();
                item.setChangeType("CHANGED");
                item.setDate(day.getDayDate());
                item.setKind(day.getDayKind());
                item.setName(day.getDayName());
                item.setOldKind(old.getDayKind());
                item.setOldName(old.getDayName());
                items.add(item);
                changed++;
            }
        }
        for (HolidayDay old : oldMap.values()) {
            if (!newMap.containsKey(old.getDayDate())) {
                DayDiffItem item = new DayDiffItem();
                item.setChangeType("REMOVED");
                item.setDate(old.getDayDate());
                item.setKind(old.getDayKind());
                item.setName(old.getDayName());
                item.setOldKind(old.getDayKind());
                item.setOldName(old.getDayName());
                items.add(item);
                removed++;
            }
        }
        items.sort(Comparator.comparing(DayDiffItem::getDate));

        ImportResultView view = new ImportResultView();
        view.setCalendar(calendar);
        view.setVersion(version);
        view.setDays(newDays);
        view.setDiffItems(items);
        view.setAddedCount(added);
        view.setRemovedCount(removed);
        view.setChangedCount(changed);
        view.setSameAsEffective(effective != null && items.isEmpty());
        return view;
    }

    private int nextVersionNo(long calendarId) {
        HolidayCalendarVersion max = versionMapper.selectOne(new LambdaQueryWrapper<HolidayCalendarVersion>()
                .eq(HolidayCalendarVersion::getCalendarId, calendarId)
                .orderByDesc(HolidayCalendarVersion::getVersionNo)
                .last("LIMIT 1"));
        return max == null ? 1 : max.getVersionNo() + 1;
    }

    private String generateNo() {
        long seq = calendarMapper.nextCalendarSeq();
        return String.format("HC%010d", seq);
    }
}
