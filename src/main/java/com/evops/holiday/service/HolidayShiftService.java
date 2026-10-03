package com.evops.holiday.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.holiday.HolidayEnums;
import com.evops.holiday.dto.ShiftBatchView;
import com.evops.holiday.dto.ShiftExecuteRequest;
import com.evops.holiday.dto.ShiftPreviewView;
import com.evops.holiday.entity.HolidayCalendar;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.entity.HolidayShiftBatch;
import com.evops.holiday.entity.HolidayShiftRecord;
import com.evops.holiday.mapper.HolidayShiftBatchMapper;
import com.evops.holiday.mapper.HolidayShiftRecordMapper;
import com.evops.install.InstallEnums;
import com.evops.install.entity.InstallOrder;
import com.evops.install.mapper.InstallOrderMapper;
import com.evops.install.service.InstallDispatchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 假期顺延域：计划落在休息日的在途安装派单，顺移到下一个工作日。
 *
 * 口径：
 * - 顺延依据 = 某年生效版假期日历；只挡显式维护的休息日（普通周末照常派单），调休上班日是工作日、不动；
 * - 命中 = 在途（待匹配/已派单）且计划时段 [planStart, planEnd) 覆盖到休息日；
 *   新档期 = 整体往后平移整数天（时刻与时长不变），直到覆盖日期不含休息日；
 * - 先出预览（逐单给出新档期与可挪性预检），确认后再批量执行；
 * - 队档期撞了/资质不合格不硬挪：记录行标 FAILED 带原因，单留原档期人工改期；
 * - 一批一记录：批次（依据版本、扫描/顺延/失败数、经手人）+ 逐单记录（原档期/新档期），
 *   每单另写 HOLIDAY_SHIFT 事件与改期通知，扯皮有据可查；
 * - 幂等：挪过的单不再落在休息日，重扫自然不再命中；并发改档由条件更新兜底。
 */
@Service
public class HolidayShiftService {

    /** 连续顺延上限（防止整年都被标成休息日死循环） */
    private static final int MAX_SHIFT_DAYS = 62;

    private final HolidayCalendarService calendarService;
    private final InstallOrderMapper installOrderMapper;
    private final InstallDispatchService installDispatchService;
    private final HolidayShiftBatchMapper batchMapper;
    private final HolidayShiftRecordMapper recordMapper;

    public HolidayShiftService(HolidayCalendarService calendarService,
                               InstallOrderMapper installOrderMapper,
                               InstallDispatchService installDispatchService,
                               HolidayShiftBatchMapper batchMapper,
                               HolidayShiftRecordMapper recordMapper) {
        this.calendarService = calendarService;
        this.installOrderMapper = installOrderMapper;
        this.installDispatchService = installDispatchService;
        this.batchMapper = batchMapper;
        this.recordMapper = recordMapper;
    }

    // ---- 预览（不落库） ----

    /** 顺延预览：逐单给出新档期与可挪性预检（队档期冲突等先标出来），确认后再批量执行。 */
    public ShiftPreviewView preview(int year, Viewer viewer) {
        viewer.requireSystemAdmin();
        HolidayCalendar calendar = requireEffectiveCalendar(year);
        HolidayCalendarVersion effective = calendarService.currentEffective(calendar.getId());
        Set<LocalDate> restDates = calendarService.effectiveRestDates(calendar.getId());

        ShiftPreviewView view = new ShiftPreviewView();
        view.setCalendarId(calendar.getId());
        view.setCalendarNo(calendar.getCalendarNo());
        view.setCalYear(calendar.getCalYear());
        view.setVersionId(effective.getId());
        view.setVersionNo(effective.getVersionNo());

        List<ShiftPreviewView.ShiftPreviewItem> items = new ArrayList<>();
        int feasible = 0;
        for (InstallOrder dispatch : findShiftable(year, restDates)) {
            ShiftPreviewView.ShiftPreviewItem item = new ShiftPreviewView.ShiftPreviewItem();
            item.setDispatchNo(dispatch.getDispatchNo());
            item.setOrderNo(dispatch.getOrderNo());
            item.setSiteNo(dispatch.getSiteNo());
            item.setTeamNo(dispatch.getTeamNo());
            item.setTeamName(dispatch.getTeamNameSnapshot());
            item.setDispatchStatus(dispatch.getStatus());
            item.setOldPlanStart(dispatch.getPlanStart());
            item.setOldPlanEnd(dispatch.getPlanEnd());
            LocalDateTime[] shifted = shiftedWindow(dispatch.getPlanStart(), dispatch.getPlanEnd(), restDates);
            item.setNewPlanStart(shifted[0]);
            item.setNewPlanEnd(shifted[1]);
            item.setRestDays(restDaysHit(dispatch.getPlanStart(), dispatch.getPlanEnd(), restDates));
            String block = installDispatchService.holidayShiftBlockReason(dispatch, shifted[0], shifted[1]);
            item.setFeasible(block == null);
            item.setBlockReason(block);
            if (block == null) {
                feasible++;
            }
            items.add(item);
        }
        view.setItems(items);
        view.setHitCount(items.size());
        view.setFeasibleCount(feasible);
        view.setBlockedCount(items.size() - feasible);
        return view;
    }

    // ---- 批量执行（确认后） ----

    /**
     * 批量顺延：一个事务建批次、逐单顺延（已派单通知到队）、插记录行、回写批次计数。
     * 单张失败不影响整批：记 FAILED 留原档期；挪过的单重扫不再命中，重复触发幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public ShiftBatchView execute(ShiftExecuteRequest request, Viewer viewer) {
        viewer.requireSystemAdmin();
        int year = request.getYear();
        HolidayCalendar calendar = requireEffectiveCalendar(year);
        HolidayCalendarVersion effective = calendarService.currentEffective(calendar.getId());
        Set<LocalDate> restDates = calendarService.effectiveRestDates(calendar.getId());

        List<InstallOrder> hits = findShiftable(year, restDates);
        if (request.getDispatchNos() != null && !request.getDispatchNos().isEmpty()) {
            Set<String> wanted = request.getDispatchNos().stream()
                    .filter(s -> s != null && !s.trim().isEmpty())
                    .map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new));
            hits = hits.stream().filter(d -> wanted.contains(d.getDispatchNo())).collect(Collectors.toList());
        }
        if (hits.isEmpty()) {
            throw new BusinessException(year + " 年没有计划落在休息日的在途派单，无需顺延");
        }

        String reason = request.getReason() == null || request.getReason().trim().isEmpty()
                ? "假期日历调整，休息日顺延" : request.getReason().trim();
        HolidayShiftBatch batch = new HolidayShiftBatch();
        batch.setBatchNo(generateBatchNo());
        batch.setCalendarId(calendar.getId());
        batch.setCalendarVersionId(effective.getId());
        batch.setCalendarVersionNo(effective.getVersionNo());
        batch.setCalYear(year);
        batch.setScannedCount(hits.size());
        batch.setShiftedCount(0);
        batch.setFailedCount(0);
        batch.setReason(reason);
        batch.setOperatorName(viewer.getUsername());
        batchMapper.insert(batch);

        int shifted = 0;
        int failed = 0;
        List<HolidayShiftRecord> records = new ArrayList<>();
        for (InstallOrder dispatch : hits) {
            LocalDateTime[] target = shiftedWindow(dispatch.getPlanStart(), dispatch.getPlanEnd(), restDates);
            HolidayShiftRecord record = new HolidayShiftRecord();
            record.setBatchId(batch.getId());
            record.setBatchNo(batch.getBatchNo());
            record.setDispatchId(dispatch.getId());
            record.setDispatchNo(dispatch.getDispatchNo());
            record.setOrderNo(dispatch.getOrderNo());
            record.setSiteNo(dispatch.getSiteNo());
            record.setTeamNo(dispatch.getTeamNo());
            record.setOldPlanStart(dispatch.getPlanStart());
            record.setOldPlanEnd(dispatch.getPlanEnd());
            try {
                installDispatchService.holidayShift(dispatch.getDispatchNo(), target[0], target[1],
                        viewer.getUsername(), reason + "（批次 " + batch.getBatchNo() + "）");
                record.setNewPlanStart(target[0]);
                record.setNewPlanEnd(target[1]);
                record.setStatus(HolidayEnums.RECORD_SHIFTED);
                shifted++;
            } catch (BusinessException e) {
                // 不硬挪：单留原档期，记录失败原因，人工改期
                record.setStatus(HolidayEnums.RECORD_FAILED);
                record.setFailReason(trimTo(e.getMessage(), 255));
                failed++;
            }
            recordMapper.insert(record);
            records.add(record);
        }

        HolidayShiftBatch patch = new HolidayShiftBatch();
        patch.setId(batch.getId());
        patch.setShiftedCount(shifted);
        patch.setFailedCount(failed);
        batchMapper.updateById(patch);
        batch.setShiftedCount(shifted);
        batch.setFailedCount(failed);

        ShiftBatchView view = new ShiftBatchView();
        view.setBatch(batch);
        view.setRecords(records);
        return view;
    }

    // ---- 批次翻查 ----

    public List<HolidayShiftBatch> listBatches(Viewer viewer) {
        viewer.requireSystemAdmin();
        return batchMapper.selectList(new LambdaQueryWrapper<HolidayShiftBatch>()
                .orderByDesc(HolidayShiftBatch::getId));
    }

    public ShiftBatchView batchDetail(String batchNo, Viewer viewer) {
        viewer.requireSystemAdmin();
        HolidayShiftBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<HolidayShiftBatch>()
                .eq(HolidayShiftBatch::getBatchNo, batchNo == null ? "" : batchNo.trim()));
        if (batch == null) {
            throw new BusinessException("顺延批次不存在: " + batchNo);
        }
        ShiftBatchView view = new ShiftBatchView();
        view.setBatch(batch);
        view.setRecords(recordMapper.selectList(new LambdaQueryWrapper<HolidayShiftRecord>()
                .eq(HolidayShiftRecord::getBatchId, batch.getId())
                .orderByAsc(HolidayShiftRecord::getId)));
        return view;
    }

    // ---- 顺延规则（只挡显式维护的休息日；调休上班日是工作日、不动） ----

    /** 新档期：整体往后平移整数天（时刻与时长不变），直到计划覆盖日期不含休息日。 */
    static LocalDateTime[] shiftedWindow(LocalDateTime start, LocalDateTime end, Set<LocalDate> restDates) {
        LocalDateTime s = start;
        LocalDateTime e = end;
        int guard = 0;
        while (touchesRest(s, e, restDates)) {
            if (++guard > MAX_SHIFT_DAYS) {
                throw new BusinessException("连续 " + MAX_SHIFT_DAYS + " 天都是休息日，无法自动顺延，请检查假期日历");
            }
            s = s.plusDays(1);
            e = e.plusDays(1);
        }
        return new LocalDateTime[]{s, e};
    }

    /** 计划时段 [start, end) 覆盖的日期里是否含休息日。 */
    static boolean touchesRest(LocalDateTime start, LocalDateTime end, Set<LocalDate> restDates) {
        return !restDaysHit(start, end, restDates).isEmpty();
    }

    /** 计划时段覆盖的日期中落在休息日集合里的那些天（半开区间 [start, end)）。 */
    static List<LocalDate> restDaysHit(LocalDateTime start, LocalDateTime end, Set<LocalDate> restDates) {
        List<LocalDate> hits = new ArrayList<>();
        LocalDate date = start.toLocalDate();
        LocalDate last = end.minusNanos(1).toLocalDate();
        while (!date.isAfter(last)) {
            if (restDates.contains(date)) {
                hits.add(date);
            }
            date = date.plusDays(1);
        }
        return hits;
    }

    // ---- 内部 ----

    /** 该年生效日历（顺延依据）；没有日历或还没发布生效版都直接拒。 */
    private HolidayCalendar requireEffectiveCalendar(int year) {
        HolidayCalendar calendar = calendarService.findByYear(year);
        if (calendar == null) {
            throw new BusinessException(year + " 年还没有假期日历，请先按年导入并发布");
        }
        if (calendarService.currentEffective(calendar.getId()) == null) {
            throw new BusinessException(year + " 年假期日历还没有生效版本，请先发布；顺延以生效日历为据");
        }
        return calendar;
    }

    /** 命中顺延的在途派单：待匹配/已派单 且 计划时段覆盖到该年生效日历的休息日。 */
    private List<InstallOrder> findShiftable(int year, Set<LocalDate> restDates) {
        if (restDates.isEmpty()) {
            return new ArrayList<>();
        }
        LocalDateTime yearStart = LocalDate.of(year, 1, 1).atStartOfDay();
        LocalDateTime yearEnd = LocalDate.of(year + 1, 1, 1).atStartOfDay();
        List<InstallOrder> open = installOrderMapper.selectList(new LambdaQueryWrapper<InstallOrder>()
                .in(InstallOrder::getStatus, InstallEnums.STATUS_PENDING, InstallEnums.STATUS_ASSIGNED)
                .isNotNull(InstallOrder::getPlanStart)
                .lt(InstallOrder::getPlanStart, yearEnd)
                .gt(InstallOrder::getPlanEnd, yearStart)
                .orderByAsc(InstallOrder::getId));
        List<InstallOrder> hits = new ArrayList<>();
        for (InstallOrder dispatch : open) {
            if (touchesRest(dispatch.getPlanStart(), dispatch.getPlanEnd(), restDates)) {
                hits.add(dispatch);
            }
        }
        return hits;
    }

    private String generateBatchNo() {
        long seq = batchMapper.nextBatchSeq();
        return String.format("HS%010d", seq);
    }

    private String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
