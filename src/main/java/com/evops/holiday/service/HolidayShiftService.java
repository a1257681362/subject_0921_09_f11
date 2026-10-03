package com.evops.holiday.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.holiday.HolidayEnums;
import com.evops.holiday.dto.ShiftPreviewRequest;
import com.evops.holiday.dto.ShiftPreviewView;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.entity.HolidayShiftBatch;
import com.evops.holiday.entity.HolidayShiftRecord;
import com.evops.holiday.mapper.HolidayShiftBatchMapper;
import com.evops.holiday.mapper.HolidayShiftRecordMapper;
import com.evops.holiday.support.HolidayProperties;
import com.evops.holiday.support.WorkingDayCalendar;
import com.evops.install.InstallEnums;
import com.evops.install.entity.InstallOrder;
import com.evops.install.mapper.InstallOrderMapper;
import com.evops.install.service.InstallDispatchService;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.entity.SlotBooking;
import com.evops.ledger.mapper.PostingOrderMapper;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.mapper.SlotBookingMapper;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.support.WeekRanges;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 档期顺延批次域：预览（不落单、只建 PREVIEWED 批次+逐行计划）→ 确认批量执行。
 *
 * 口径：
 * - 命中：挂起中、合规正常、发生日在范围内且发生日是休息日（日历 REST 或无条目回退的普通周末）；
 *   调休上班日 WORK 是工作日，发生日落它不命中，顺延到它当天也停；
 * - 下一工作日与周档期由 WorkingDayCalendar + WeekRanges 一处算；目标周被别的挂起单占用预演标冲突，
 *   确认时占位仍在则该行 SKIPPED，不拖垮整批；
 * - 安装派单已到场/完工的单整单拦截不挪（blocked）；其余派单确认时联动（改期通知/冲突转待匹配）；
 * - 确认逐行重新读当前态，CAS 到单上（见 OrderService.applyHolidayShift），重复确认/并发幂等。
 */
@Service
public class HolidayShiftService {

    private final PostingOrderMapper orderMapper;
    private final SlotBookingMapper slotMapper;
    private final SiteMapper siteMapper;
    private final InstallOrderMapper installOrderMapper;
    private final HolidayShiftBatchMapper batchMapper;
    private final HolidayShiftRecordMapper recordMapper;
    private final HolidayCalendarService calendarService;
    private final WorkingDayCalendar workingDayCalendar;
    private final HolidayProperties properties;
    private final OrderService orderService;
    private final InstallDispatchService installDispatchService;

    public HolidayShiftService(PostingOrderMapper orderMapper,
                               SlotBookingMapper slotMapper,
                               SiteMapper siteMapper,
                               InstallOrderMapper installOrderMapper,
                               HolidayShiftBatchMapper batchMapper,
                               HolidayShiftRecordMapper recordMapper,
                               HolidayCalendarService calendarService,
                               WorkingDayCalendar workingDayCalendar,
                               HolidayProperties properties,
                               OrderService orderService,
                               InstallDispatchService installDispatchService) {
        this.orderMapper = orderMapper;
        this.slotMapper = slotMapper;
        this.siteMapper = siteMapper;
        this.installOrderMapper = installOrderMapper;
        this.batchMapper = batchMapper;
        this.recordMapper = recordMapper;
        this.calendarService = calendarService;
        this.workingDayCalendar = workingDayCalendar;
        this.properties = properties;
        this.orderService = orderService;
        this.installDispatchService = installDispatchService;
    }

    /**
     * 顺延预览：建 PREVIEWED 批次与 PLANNED 逐行计划（不动任何单据），返回批次句柄与逐行结果。
     * 运营拿 batchNo 确认；批次本身就是"预览当时的账"，扯皮可翻。
     */
    @Transactional(rollbackFor = Exception.class)
    public ShiftPreviewView preview(ShiftPreviewRequest request, Viewer viewer) {
        viewer.requireCalendarAdmin();
        LocalDate from = request.getFrom();
        LocalDate to = request.getTo();
        if (from == null || to == null) {
            throw new BusinessException("顺延范围起止日期都必填");
        }
        if (to.isBefore(from)) {
            throw new BusinessException("顺延范围结束日不能早于开始日");
        }

        LambdaQueryWrapper<PostingOrder> w = new LambdaQueryWrapper<PostingOrder>()
                .eq(PostingOrder::getStatus, LedgerEnums.ORDER_HUNG)
                .eq(PostingOrder::getComplianceStatus, LedgerEnums.COMPLIANCE_NORMAL)
                .ge(PostingOrder::getOccurDate, from)
                .le(PostingOrder::getOccurDate, to);
        if (request.getSiteNo() != null && !request.getSiteNo().trim().isEmpty()) {
            Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>()
                    .eq(Site::getSiteNo, request.getSiteNo().trim()));
            if (site == null) {
                throw new BusinessException("点位不存在: " + request.getSiteNo());
            }
            w.eq(PostingOrder::getSiteId, site.getId());
        }
        List<PostingOrder> orders = orderMapper.selectList(w.orderByAsc(PostingOrder::getId));

        HolidayCalendarVersion version = workingDayCalendar.effectiveVersion(from.getYear());
        HolidayShiftBatch batch = new HolidayShiftBatch();
        batch.setBatchNo(generateBatchNo());
        batch.setCalYear(from.getYear());
        batch.setCalendarVersionId(version == null ? null : version.getId());
        batch.setCalendarVersionNo(version == null ? null : version.getVersionNo());
        batch.setScopeFrom(from);
        batch.setScopeTo(to);
        batch.setSiteNo(request.getSiteNo() == null || request.getSiteNo().trim().isEmpty()
                ? null : request.getSiteNo().trim());
        batch.setStatus(HolidayEnums.BATCH_PREVIEWED);
        batch.setReason(request.getReason() == null || request.getReason().trim().isEmpty()
                ? "假期档期顺延" : request.getReason().trim());
        batch.setOperatorName(viewer.getUsername());
        batch.setHitCount(0);
        batch.setShiftedCount(0);
        batch.setSkippedCount(0);
        batch.setBlockedCount(0);
        batchMapper.insert(batch);

        ShiftPreviewView view = new ShiftPreviewView();
        view.setBatchNo(batch.getBatchNo());
        view.setCalYear(from.getYear());
        view.setCalendarVersionNo(version == null ? null : version.getVersionNo());
        view.setScopeFrom(from);
        view.setScopeTo(to);
        view.setSiteNo(batch.getSiteNo());
        List<ShiftPreviewView.ShiftRow> shiftRows = new ArrayList<>();
        List<ShiftPreviewView.ShiftRow> blockedRows = new ArrayList<>();

        int hit = 0, blocked = 0;
        for (PostingOrder o : orders) {
            String restName = restReason(o.getOccurDate());
            if (restName == null) {
                continue;
            }
            LocalDate newOccur;
            try {
                newOccur = workingDayCalendar.nextWorkingDay(o.getOccurDate(), properties.getMaxScanDays());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(e.getMessage());
            }
            LocalDate newWeekStart = WeekRanges.mondayOf(newOccur);
            LocalDate newWeekEnd = WeekRanges.sundayOf(newOccur);
            String conflict = activeSlotOwner(o.getSiteId(), newWeekStart, o.getId());
            InstallOrder dispatch = installOrderMapper.selectOne(new LambdaQueryWrapper<InstallOrder>()
                    .eq(InstallOrder::getOrderId, o.getId()));
            boolean arrivedOrDone = dispatch != null
                    && (InstallEnums.STATUS_ARRIVED.equals(dispatch.getStatus())
                    || InstallEnums.STATUS_COMPLETED.equals(dispatch.getStatus()));

            ShiftPreviewView.ShiftRow row = new ShiftPreviewView.ShiftRow();
            row.setOrderId(o.getId());
            row.setOrderNo(o.getOrderNo());
            row.setSiteNo(siteNoOf(o.getSiteId()));
            row.setCreativeName(o.getCreativeName());
            row.setOldOccurDate(o.getOccurDate());
            row.setOldWeekStart(o.getWeekStart());
            row.setOldWeekEnd(o.getWeekEnd());
            row.setNewOccurDate(newOccur);
            row.setNewWeekStart(newWeekStart);
            row.setNewWeekEnd(newWeekEnd);
            row.setHitRestName(restName);
            row.setConflictOrderNo(conflict);
            row.setInstallDispatchNo(dispatch == null ? null : dispatch.getDispatchNo());
            row.setInstallAction(dispatch == null
                    ? InstallEnums.INSTALL_NONE
                    : (arrivedOrDone ? InstallEnums.INSTALL_NOT_DONE
                    : InstallEnums.STATUS_CANCELLED.equals(dispatch.getStatus())
                    ? InstallEnums.INSTALL_NONE : InstallEnums.INSTALL_RESCHEDULED));

            if (arrivedOrDone) {
                blocked++;
                row.setBlockReason("安装派单 " + dispatch.getDispatchNo() + " 已"
                        + InstallEnums.statusLabel(dispatch.getStatus()) + "，安装已实际发生，不自动挪单");
                blockedRows.add(row);
                continue;
            }
            hit++;
            shiftRows.add(row);

            HolidayShiftRecord record = new HolidayShiftRecord();
            record.setBatchId(batch.getId());
            record.setBatchNo(batch.getBatchNo());
            record.setOrderId(o.getId());
            record.setOrderNo(o.getOrderNo());
            record.setSiteNo(row.getSiteNo());
            record.setCreativeName(o.getCreativeName());
            record.setOldOccurDate(o.getOccurDate());
            record.setOldWeekStart(o.getWeekStart());
            record.setOldWeekEnd(o.getWeekEnd());
            record.setNewOccurDate(newOccur);
            record.setNewWeekStart(newWeekStart);
            record.setNewWeekEnd(newWeekEnd);
            record.setHitRestName(restName);
            record.setConflictOrderNo(conflict);
            record.setInstallDispatchNo(row.getInstallDispatchNo());
            record.setInstallAction(row.getInstallAction());
            record.setStatus(HolidayEnums.RECORD_PLANNED);
            recordMapper.insert(record);
        }

        HolidayShiftBatch patch = new HolidayShiftBatch();
        patch.setId(batch.getId());
        patch.setHitCount(hit);
        patch.setBlockedCount(blocked);
        batchMapper.updateById(patch);
        batch.setHitCount(hit);
        batch.setBlockedCount(blocked);

        view.setHitCount(hit);
        view.setBlockedCount(blocked);
        view.setShifts(shiftRows);
        view.setBlocked(blockedRows);
        return view;
    }

    /**
     * 确认批量顺延：批次 CAS PREVIEWED→CONFIRMED（重复确认拒）；逐行重读当前态执行，
     * 挪不动（单据已关闭/冻结/发生日已变/目标周仍被占）记 SKIPPED 并写原因，其余 SHIFTED 并联动安装派单。
     * 一个事务整批收口。
     */
    @Transactional(rollbackFor = Exception.class)
    public HolidayShiftBatch confirm(String batchNo, Viewer viewer) {
        viewer.requireCalendarAdmin();
        HolidayShiftBatch batch = requireBatch(batchNo);
        if (!HolidayEnums.BATCH_PREVIEWED.equals(batch.getStatus())) {
            throw new BusinessException("顺延批次 " + batchNo + " 已"
                    + HolidayEnums.batchStatusLabel(batch.getStatus()) + "，不能重复确认");
        }
        int cas = batchMapper.update(null, new LambdaUpdateWrapper<HolidayShiftBatch>()
                .eq(HolidayShiftBatch::getId, batch.getId())
                .eq(HolidayShiftBatch::getStatus, HolidayEnums.BATCH_PREVIEWED)
                .set(HolidayShiftBatch::getStatus, HolidayEnums.BATCH_CONFIRMED)
                .set(HolidayShiftBatch::getConfirmTime, LocalDateTime.now())
                .set(HolidayShiftBatch::getConfirmOperator, viewer.getUsername()));
        if (cas == 0) {
            throw new BusinessException("顺延批次 " + batchNo + " 刚被确认，请刷新");
        }
        batch.setStatus(HolidayEnums.BATCH_CONFIRMED);

        List<HolidayShiftRecord> records = recordMapper.selectList(new LambdaQueryWrapper<HolidayShiftRecord>()
                .eq(HolidayShiftRecord::getBatchId, batch.getId())
                .orderByAsc(HolidayShiftRecord::getId));
        int shifted = 0, skipped = 0;
        for (HolidayShiftRecord r : records) {
            PostingOrder order = orderMapper.selectById(r.getOrderId());
            String skipReason = checkSkip(order, r);
            if (skipReason != null) {
                markSkipped(r, skipReason);
                skipped++;
                continue;
            }
            boolean done = orderService.applyHolidayShift(order, r.getOldOccurDate(), r.getNewOccurDate(),
                    batch.getBatchNo(), batch.getCalendarVersionNo(), r.getHitRestName(), viewer.getUsername());
            if (!done) {
                markSkipped(r, "确认时单据状态已变或目标周档期刚被占用");
                skipped++;
                continue;
            }
            // 联动安装派单（实际档期空闲校验在这一步，可能 RESCHEDULED 或 TO_PENDING）
            String installAction = InstallEnums.INSTALL_NONE;
            String dispatchNo = r.getInstallDispatchNo();
            if (r.getInstallDispatchNo() != null) {
                InstallDispatchService.HolidayShiftLink link = installDispatchService.handleHolidayShift(
                        order, r.getNewOccurDate(), batch.getBatchNo(),
                        batch.getCalendarVersionNo(), viewer.getUsername());
                installAction = link.getAction();
                dispatchNo = link.getDispatchNo();
            }
            HolidayShiftRecord rp = new HolidayShiftRecord();
            rp.setId(r.getId());
            rp.setStatus(HolidayEnums.RECORD_SHIFTED);
            rp.setInstallAction(installAction);
            rp.setInstallDispatchNo(dispatchNo);
            recordMapper.updateById(rp);
            r.setStatus(HolidayEnums.RECORD_SHIFTED);
            shifted++;
        }

        HolidayShiftBatch patch = new HolidayShiftBatch();
        patch.setId(batch.getId());
        patch.setShiftedCount(shifted);
        patch.setSkippedCount(skipped);
        batchMapper.updateById(patch);
        batch.setShiftedCount(shifted);
        batch.setSkippedCount(skipped);
        return batch;
    }

    // ---- 翻查 ----

    public List<HolidayShiftBatch> listBatches(String status) {
        LambdaQueryWrapper<HolidayShiftBatch> w = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            w.eq(HolidayShiftBatch::getStatus, status.trim().toUpperCase());
        }
        return batchMapper.selectList(w.orderByDesc(HolidayShiftBatch::getId));
    }

    public HolidayShiftBatch requireBatch(String batchNo) {
        if (batchNo == null || batchNo.trim().isEmpty()) {
            throw new BusinessException("顺延批次号不能为空");
        }
        HolidayShiftBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<HolidayShiftBatch>()
                .eq(HolidayShiftBatch::getBatchNo, batchNo.trim()));
        if (batch == null) {
            throw new BusinessException("顺延批次不存在: " + batchNo);
        }
        return batch;
    }

    public List<HolidayShiftRecord> recordsOf(Long batchId) {
        return recordMapper.selectList(new LambdaQueryWrapper<HolidayShiftRecord>()
                .eq(HolidayShiftRecord::getBatchId, batchId)
                .orderByAsc(HolidayShiftRecord::getId));
    }

    /** 按上刊单号查全部顺延留痕（PLANNED/SHIFTED/SKIPPED 都在），扯皮按单翻账。 */
    public List<HolidayShiftRecord> recordsOfOrder(String orderNo) {
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw new BusinessException("上刊单号不能为空");
        }
        return recordMapper.selectList(new LambdaQueryWrapper<HolidayShiftRecord>()
                .eq(HolidayShiftRecord::getOrderNo, orderNo.trim())
                .orderByAsc(HolidayShiftRecord::getId));
    }

    // ---- 内部 ----

    /** 确认前的静态跳过判定（动态档期占用由 OrderService 内的目标周判定兜底）。 */
    private String checkSkip(PostingOrder order, HolidayShiftRecord r) {
        if (order == null) {
            return "单据已不存在";
        }
        if (!LedgerEnums.ORDER_HUNG.equals(order.getStatus())) {
            return "单据已" + order.getStatus() + " 关闭，不再顺延";
        }
        if (LedgerEnums.COMPLIANCE_FROZEN.equals(order.getComplianceStatus())) {
            return "单据合规冻结中，不能顺延";
        }
        if (!r.getOldOccurDate().equals(order.getOccurDate())) {
            return "发生日已不是预览当时的 " + r.getOldOccurDate()
                    + "（现为 " + order.getOccurDate() + "），可能已被别的批次挪过";
        }
        return null;
    }

    private void markSkipped(HolidayShiftRecord r, String reason) {
        HolidayShiftRecord patch = new HolidayShiftRecord();
        patch.setId(r.getId());
        patch.setStatus(HolidayEnums.RECORD_SKIPPED);
        patch.setSkipReason(reason);
        recordMapper.updateById(patch);
        r.setStatus(HolidayEnums.RECORD_SKIPPED);
    }

    /**
     * 发生日的休息日依据：日历 REST 条目→节假日名；无条目但落普通周末→"周末休息"；
     * 工作日（普通工作日/调休上班日）→null（不命中）。
     */
    private String restReason(LocalDate date) {
        com.evops.holiday.entity.HolidayDay entry = workingDayCalendar.findEntry(date);
        if (entry != null) {
            return HolidayEnums.DAY_REST.equals(entry.getDayType())
                    ? (entry.getHolidayName() == null || entry.getHolidayName().isEmpty()
                    ? "休息日" : entry.getHolidayName())
                    : null;
        }
        java.time.DayOfWeek dow = date.getDayOfWeek();
        return dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY ? "周末休息" : null;
    }

    /** 目标周 ACTIVE 档期占用方（排除本单）；空=可落。 */
    private String activeSlotOwner(Long siteId, LocalDate weekStart, Long excludeOrderId) {
        SlotBooking slot = slotMapper.selectOne(new LambdaQueryWrapper<SlotBooking>()
                .eq(SlotBooking::getSiteId, siteId)
                .eq(SlotBooking::getWeekStart, weekStart)
                .last("LIMIT 1"));
        if (slot == null || !LedgerEnums.SLOT_ACTIVE.equals(slot.getStatus())
                || slot.getOrderId().equals(excludeOrderId)) {
            return null;
        }
        return slot.getOrderNo();
    }

    private String siteNoOf(Long siteId) {
        Site site = siteMapper.selectById(siteId);
        return site == null ? String.valueOf(siteId) : site.getSiteNo();
    }

    private String generateBatchNo() {
        return String.format("HS%010d", batchMapper.nextBatchSeq());
    }
}
