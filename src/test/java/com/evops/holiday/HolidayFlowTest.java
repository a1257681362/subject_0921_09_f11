package com.evops.holiday;

import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.holiday.dto.CalendarImportRequest;
import com.evops.holiday.dto.DayDiffItem;
import com.evops.holiday.dto.DaySpec;
import com.evops.holiday.dto.ImportResultView;
import com.evops.holiday.dto.ShiftBatchView;
import com.evops.holiday.dto.ShiftExecuteRequest;
import com.evops.holiday.dto.ShiftPreviewView;
import com.evops.holiday.entity.HolidayCalendar;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.entity.HolidayShiftRecord;
import com.evops.holiday.service.HolidayCalendarService;
import com.evops.holiday.service.HolidayShiftService;
import com.evops.install.InstallEnums;
import com.evops.install.dto.InstallDetailView;
import com.evops.install.dto.ManualMatchRequest;
import com.evops.install.dto.TeamCreateRequest;
import com.evops.install.entity.InstallOrder;
import com.evops.install.entity.InstallTeam;
import com.evops.install.service.InstallDispatchService;
import com.evops.install.service.InstallTeamService;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.SiteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F11 假期日历与安装派单顺延端到端（真实 H2 + 真实事务，无 Shiro，用显式 Viewer）。
 * 类级 {@link Transactional}：每个用例结束回滚，日历/派单互不串库。
 * 专用年份 2098/2099（其他测试不碰），顺延扫描按年过滤，计数确定可靠。
 * 覆盖：按年导入与差异比对、发布取代、导入校验、休息日顺延/调休上班日不动、
 * 待匹配单顺延、队档期冲突不硬挪、留痕（事件+通知+批次记录）、幂等、权限。
 */
@SpringBootTest
@Transactional
class HolidayFlowTest {

    @Autowired private HolidayCalendarService calendarService;
    @Autowired private HolidayShiftService shiftService;
    @Autowired private OrderService orderService;
    @Autowired private SiteService siteService;
    @Autowired private InstallTeamService teamService;
    @Autowired private InstallDispatchService dispatchService;

    private static final Viewer SYSTEM = Viewer.system("demo");
    private static final int YEAR = 2099;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    private Site registerSite() {
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("HS"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝南");
        sr.setBusinessDistrict("假期测试商圈");
        sr.setOperatorName("tester");
        return siteService.register(sr);
    }

    /** 上刊（普通单），occurDate 所在 ISO 周的周一 09:00~13:00 为派单默认档期。 */
    private PostingOrder post(Site site, String creative, LocalDate occurDate) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setCreativeName(creative);
        req.setIndustryCategory("快消品");
        req.setOccurDate(occurDate);
        req.setOperatorName("tester");
        return orderService.createPosting(req);
    }

    private InstallTeam createTeam(String name) {
        TeamCreateRequest t = new TeamCreateRequest();
        t.setTeamName(uniqueNo(name));
        t.setLeaderName("李队长");
        t.setContact("13800000000");
        t.setOperatorName("dispatcher");
        return teamService.create(t);
    }

    private DaySpec day(String date, String kind, String name) {
        DaySpec spec = new DaySpec();
        spec.setDate(LocalDate.parse(date));
        spec.setKind(kind);
        spec.setName(name);
        return spec;
    }

    private CalendarImportRequest importReq(int year, DaySpec... days) {
        CalendarImportRequest req = new CalendarImportRequest();
        req.setYear(year);
        req.setDays(new ArrayList<>(Arrays.asList(days)));
        req.setChangeSummary(year + " 年国务院节假日安排");
        return req;
    }

    /** 导入并直接发布，返回生效版本。 */
    private HolidayCalendarVersion importAndPublish(int year, DaySpec... days) {
        ImportResultView result = calendarService.importCalendar(importReq(year, days), SYSTEM);
        return calendarService.publish(result.getVersion().getId(), SYSTEM);
    }

    private ShiftPreviewView.ShiftPreviewItem previewItem(ShiftPreviewView view, String dispatchNo) {
        return view.getItems().stream()
                .filter(i -> dispatchNo.equals(i.getDispatchNo()))
                .findFirst().orElse(null);
    }

    // ---- 按年导入与差异比对 ----

    @Test
    void 按年导入建草稿出差异_发布生效旧版自动取代() {
        // 首版导入：全部新增
        ImportResultView first = calendarService.importCalendar(importReq(YEAR,
                day(YEAR + "-10-01", "REST", "国庆节"),
                day(YEAR + "-10-02", "REST", "国庆节"),
                day(YEAR + "-10-10", "MAKEUP_WORK", "调休上班")), SYSTEM);
        assertEquals(HolidayEnums.CAL_DRAFT, first.getCalendar().getStatus());
        assertEquals(HolidayEnums.VERSION_DRAFT, first.getVersion().getStatus());
        assertEquals(1, first.getVersion().getVersionNo());
        assertEquals(2, first.getVersion().getRestCount());
        assertEquals(1, first.getVersion().getMakeupCount());
        assertEquals(3, first.getAddedCount());
        assertEquals(0, first.getRemovedCount());
        assertEquals(0, first.getChangedCount());
        assertFalse(first.isSameAsEffective());

        // 发布生效
        HolidayCalendarVersion v1 = calendarService.publish(first.getVersion().getId(), SYSTEM);
        assertEquals(HolidayEnums.VERSION_EFFECTIVE, v1.getStatus());
        assertNotNull(v1.getPublishTime());
        HolidayCalendar calendar = calendarService.list(YEAR).get(0);
        assertEquals(HolidayEnums.CAL_EFFECTIVE, calendar.getStatus());
        assertEquals(1, calendar.getCurrentVersionNo());

        // 再次导入同年：另起新草稿，差异比对（新增/移除/变更各一）
        ImportResultView second = calendarService.importCalendar(importReq(YEAR,
                day(YEAR + "-10-01", "REST", "国庆节"),
                day(YEAR + "-10-02", "MAKEUP_WORK", "调休上班"),   // 休息日 -> 调休上班日：变更
                day(YEAR + "-10-03", "REST", "国庆假期")), SYSTEM); // 新增；10-10 调休上班日移除
        assertEquals(2, second.getVersion().getVersionNo());
        assertEquals(HolidayEnums.VERSION_DRAFT, second.getVersion().getStatus());
        assertEquals(1, second.getAddedCount());
        assertEquals(1, second.getRemovedCount());
        assertEquals(1, second.getChangedCount());
        List<String> changedTypes = second.getDiffItems().stream()
                .map(DayDiffItem::getChangeType).sorted().collect(Collectors.toList());
        assertEquals(Arrays.asList("ADDED", "CHANGED", "REMOVED"), changedTypes);
        DayDiffItem changed = second.getDiffItems().stream()
                .filter(i -> "CHANGED".equals(i.getChangeType())).findFirst().get();
        assertEquals(LocalDate.of(YEAR, 10, 2), changed.getDate());
        assertEquals(HolidayEnums.KIND_REST, changed.getOldKind());
        assertEquals(HolidayEnums.KIND_MAKEUP_WORK, changed.getKind());

        // 发布新版：旧生效版自动取代，版本链可翻
        calendarService.publish(second.getVersion().getId(), SYSTEM);
        List<HolidayCalendarVersion> versions = calendarService.versionsOf(calendar.getId());
        assertEquals(HolidayEnums.VERSION_EFFECTIVE, versions.get(0).getStatus());
        assertEquals(HolidayEnums.VERSION_SUPERSEDED, versions.get(1).getStatus());
        assertEquals(2, calendarService.list(YEAR).get(0).getCurrentVersionNo());
        // 生效休息日期：10-01 与 10-03（10-02 已变调休上班日）
        assertEquals(new java.util.HashSet<>(Arrays.asList(
                LocalDate.of(YEAR, 10, 1), LocalDate.of(YEAR, 10, 3))),
                calendarService.effectiveRestDates(calendar.getId()));

        // 再导入与生效版完全一致：标 sameAsEffective，提示不必发布
        ImportResultView same = calendarService.importCalendar(importReq(YEAR,
                day(YEAR + "-10-01", "REST", "国庆节"),
                day(YEAR + "-10-02", "MAKEUP_WORK", "调休上班"),
                day(YEAR + "-10-03", "REST", "国庆假期")), SYSTEM);
        assertTrue(same.isSameAsEffective());
        assertEquals(0, same.getDiffItems().size());
    }

    @Test
    void 导入校验_日期落年份内_同日只能落一边_类型合法_空表拒收() {
        BusinessException outOfYear = assertThrows(BusinessException.class,
                () -> calendarService.importCalendar(importReq(YEAR,
                        day("2098-12-31", "REST", "跨年")), SYSTEM));
        assertTrue(outOfYear.getMessage().contains("不在导入年份"));

        BusinessException dup = assertThrows(BusinessException.class,
                () -> calendarService.importCalendar(importReq(YEAR,
                        day(YEAR + "-10-01", "REST", "国庆节"),
                        day(YEAR + "-10-01", "MAKEUP_WORK", "调休上班")), SYSTEM));
        assertTrue(dup.getMessage().contains("只能落一边"));

        BusinessException badKind = assertThrows(BusinessException.class,
                () -> calendarService.importCalendar(importReq(YEAR,
                        day(YEAR + "-10-01", "HOLIDAY", "国庆节")), SYSTEM));
        assertTrue(badKind.getMessage().contains("不认识"));

        BusinessException empty = assertThrows(BusinessException.class,
                () -> calendarService.importCalendar(importReq(YEAR), SYSTEM));
        assertTrue(empty.getMessage().contains("不能为空"));

        // 已发布的版本不能再发布；没有生效版时预览/执行都拒
        ImportResultView result = calendarService.importCalendar(importReq(YEAR,
                day(YEAR + "-10-01", "REST", "国庆节")), SYSTEM);
        BusinessException notEffective = assertThrows(BusinessException.class,
                () -> shiftService.preview(YEAR, SYSTEM));
        assertTrue(notEffective.getMessage().contains("还没有生效版本"));
        calendarService.publish(result.getVersion().getId(), SYSTEM);
        BusinessException republish = assertThrows(BusinessException.class,
                () -> calendarService.publish(result.getVersion().getId(), SYSTEM));
        assertTrue(republish.getMessage().contains("不能发布"));
    }

    // ---- 顺延预览与批量执行 ----

    @Test
    void 休息日顺延到下一个工作日_调休上班日不动_留痕可翻() {
        createTeam("顺延一队");
        PostingOrder posting = post(registerSite(), "国庆画面", LocalDate.of(YEAR, 10, 5));
        InstallOrder dispatch = dispatchService.getByOrderNo(posting.getOrderNo());
        assertEquals(InstallEnums.STATUS_ASSIGNED, dispatch.getStatus());
        LocalDate monday = dispatch.getPlanStart().toLocalDate();

        // 计划日与其次日是休息日，第三天是调休上班日（工作日，不动）
        importAndPublish(YEAR,
                day(monday.toString(), "REST", "国庆节"),
                day(monday.plusDays(1).toString(), "REST", "国庆节"),
                day(monday.plusDays(2).toString(), "MAKEUP_WORK", "调休上班"));

        // 预览：命中，新档期 = 调休上班日（时刻与时长不变）
        ShiftPreviewView preview = shiftService.preview(YEAR, SYSTEM);
        assertEquals(1, preview.getHitCount());
        assertEquals(1, preview.getFeasibleCount());
        ShiftPreviewView.ShiftPreviewItem item = previewItem(preview, dispatch.getDispatchNo());
        assertNotNull(item);
        assertTrue(item.isFeasible());
        assertEquals(LocalDateTime.of(monday.plusDays(2), java.time.LocalTime.of(9, 0)), item.getNewPlanStart());
        assertEquals(LocalDateTime.of(monday.plusDays(2), java.time.LocalTime.of(13, 0)), item.getNewPlanEnd());
        assertEquals(Arrays.asList(monday), item.getRestDays());

        // 确认批量执行
        ShiftExecuteRequest exec = new ShiftExecuteRequest();
        exec.setYear(YEAR);
        ShiftBatchView done = shiftService.execute(exec, SYSTEM);
        assertEquals(1, done.getBatch().getScannedCount());
        assertEquals(1, done.getBatch().getShiftedCount());
        assertEquals(0, done.getBatch().getFailedCount());
        assertTrue(done.getBatch().getBatchNo().startsWith("HS"));

        // 逐单记录留原档期痕迹
        HolidayShiftRecord record = done.getRecords().get(0);
        assertEquals(HolidayEnums.RECORD_SHIFTED, record.getStatus());
        assertEquals(dispatch.getDispatchNo(), record.getDispatchNo());
        assertEquals(LocalDateTime.of(monday, java.time.LocalTime.of(9, 0)), record.getOldPlanStart());
        assertEquals(LocalDateTime.of(monday.plusDays(2), java.time.LocalTime.of(9, 0)), record.getNewPlanStart());

        // 派单已挪：新档期、改期次数、到场截止跟随
        InstallOrder shifted = dispatchService.requireByNo(dispatch.getDispatchNo());
        assertEquals(LocalDateTime.of(monday.plusDays(2), java.time.LocalTime.of(9, 0)), shifted.getPlanStart());
        assertEquals(LocalDateTime.of(monday.plusDays(2), java.time.LocalTime.of(13, 0)), shifted.getPlanEnd());
        assertEquals(shifted.getPlanStart(), shifted.getArrivalDeadline());
        assertEquals(1, shifted.getRescheduleCount());

        // 留痕：HOLIDAY_SHIFT 事件 + 改期通知到队（新旧档期都在）
        InstallDetailView detail = dispatchService.detail(dispatch.getDispatchNo());
        assertTrue(detail.getEvents().stream()
                .anyMatch(e -> InstallEnums.EVENT_HOLIDAY_SHIFT.equals(e.getEventType())
                        && e.getDetail().contains("假期顺延") && e.getSnapshot() != null));
        assertEquals(1, detail.getNotices().size());
        assertEquals(LocalDateTime.of(monday, java.time.LocalTime.of(9, 0)),
                detail.getNotices().get(0).getOldStart());
        assertEquals(LocalDateTime.of(monday.plusDays(2), java.time.LocalTime.of(9, 0)),
                detail.getNotices().get(0).getNewStart());

        // 幂等：挪过的单不再落在休息日，重扫不再命中；重复执行直接拒
        assertEquals(0, shiftService.preview(YEAR, SYSTEM).getHitCount());
        BusinessException again = assertThrows(BusinessException.class,
                () -> shiftService.execute(exec, SYSTEM));
        assertTrue(again.getMessage().contains("无需顺延"));

        // 批次可翻查
        ShiftBatchView reloaded = shiftService.batchDetail(done.getBatch().getBatchNo(), SYSTEM);
        assertEquals(1, reloaded.getRecords().size());
        assertEquals(1, shiftService.listBatches(SYSTEM).size());
    }

    @Test
    void 待匹配单顺延_无安装队不写通知() {
        // 不建安装队：派单留待匹配
        PostingOrder posting = post(registerSite(), "待匹配画面", LocalDate.of(YEAR, 10, 5));
        InstallOrder dispatch = dispatchService.getByOrderNo(posting.getOrderNo());
        assertEquals(InstallEnums.STATUS_PENDING, dispatch.getStatus());
        LocalDate monday = dispatch.getPlanStart().toLocalDate();

        importAndPublish(YEAR, day(monday.toString(), "REST", "国庆节"));

        ShiftPreviewView preview = shiftService.preview(YEAR, SYSTEM);
        assertEquals(1, preview.getHitCount());
        assertTrue(previewItem(preview, dispatch.getDispatchNo()).isFeasible());

        ShiftExecuteRequest exec = new ShiftExecuteRequest();
        exec.setYear(YEAR);
        ShiftBatchView done = shiftService.execute(exec, SYSTEM);
        assertEquals(1, done.getBatch().getShiftedCount());

        InstallOrder shifted = dispatchService.requireByNo(dispatch.getDispatchNo());
        assertEquals(LocalDateTime.of(monday.plusDays(1), java.time.LocalTime.of(9, 0)), shifted.getPlanStart());
        InstallDetailView detail = dispatchService.detail(dispatch.getDispatchNo());
        assertEquals(0, detail.getNotices().size());
        assertTrue(detail.getEvents().stream()
                .anyMatch(e -> InstallEnums.EVENT_HOLIDAY_SHIFT.equals(e.getEventType())
                        && e.getDetail().contains("待匹配")));
    }

    @Test
    void 队档期撞了不硬挪_标失败留原档期() {
        InstallTeam team = createTeam("冲突队");
        PostingOrder first = post(registerSite(), "先占位", LocalDate.of(YEAR, 10, 5));
        InstallOrder da = dispatchService.getByOrderNo(first.getOrderNo());
        assertEquals(InstallEnums.STATUS_ASSIGNED, da.getStatus());
        LocalDate monday = da.getPlanStart().toLocalDate();

        // 第二张单调到同一队：人工排到 monday+2（工作日空档）
        PostingOrder second = post(registerSite(), "后占位", LocalDate.of(YEAR, 10, 5));
        InstallOrder db = dispatchService.getByOrderNo(second.getOrderNo());
        assertEquals(InstallEnums.STATUS_PENDING, db.getStatus());
        ManualMatchRequest mm = new ManualMatchRequest();
        mm.setTeamNo(team.getTeamNo());
        mm.setPlanStart(LocalDateTime.of(monday.plusDays(2), java.time.LocalTime.of(9, 0)));
        mm.setPlanEnd(LocalDateTime.of(monday.plusDays(2), java.time.LocalTime.of(13, 0)));
        mm.setOperatorName("dispatcher");
        dispatchService.manualMatch(db.getDispatchNo(), mm);

        // monday 与 monday+1 是休息日：da 顺延目标正是 monday+2 09:00，撞上 db
        importAndPublish(YEAR,
                day(monday.toString(), "REST", "国庆节"),
                day(monday.plusDays(1).toString(), "REST", "国庆节"));

        ShiftPreviewView preview = shiftService.preview(YEAR, SYSTEM);
        assertEquals(1, preview.getHitCount());
        assertEquals(0, preview.getFeasibleCount());
        assertEquals(1, preview.getBlockedCount());
        ShiftPreviewView.ShiftPreviewItem item = previewItem(preview, da.getDispatchNo());
        assertFalse(item.isFeasible());
        assertTrue(item.getBlockReason().contains("同一时段"));

        ShiftExecuteRequest exec = new ShiftExecuteRequest();
        exec.setYear(YEAR);
        ShiftBatchView done = shiftService.execute(exec, SYSTEM);
        assertEquals(0, done.getBatch().getShiftedCount());
        assertEquals(1, done.getBatch().getFailedCount());
        HolidayShiftRecord record = done.getRecords().get(0);
        assertEquals(HolidayEnums.RECORD_FAILED, record.getStatus());
        assertTrue(record.getFailReason().contains("同一时段"));
        assertNull(record.getNewPlanStart());
        // 原档期留痕，派单没被硬挪
        assertEquals(LocalDateTime.of(monday, java.time.LocalTime.of(9, 0)), record.getOldPlanStart());
        assertEquals(LocalDateTime.of(monday, java.time.LocalTime.of(9, 0)),
                dispatchService.requireByNo(da.getDispatchNo()).getPlanStart());
        assertEquals(0, dispatchService.requireByNo(da.getDispatchNo()).getRescheduleCount());
    }

    @Test
    void 指定派单子集顺延_其余留待下批() {
        createTeam("子集队");
        PostingOrder p1 = post(registerSite(), "子集一", LocalDate.of(YEAR, 10, 5));
        PostingOrder p2 = post(registerSite(), "子集二", LocalDate.of(YEAR, 10, 12));
        InstallOrder d1 = dispatchService.getByOrderNo(p1.getOrderNo());
        InstallOrder d2 = dispatchService.getByOrderNo(p2.getOrderNo());
        LocalDate monday1 = d1.getPlanStart().toLocalDate();
        LocalDate monday2 = d2.getPlanStart().toLocalDate();

        importAndPublish(YEAR,
                day(monday1.toString(), "REST", "国庆节"),
                day(monday2.toString(), "REST", "国庆节"));

        assertEquals(2, shiftService.preview(YEAR, SYSTEM).getHitCount());

        ShiftExecuteRequest exec = new ShiftExecuteRequest();
        exec.setYear(YEAR);
        exec.setDispatchNos(Arrays.asList(d1.getDispatchNo()));
        ShiftBatchView done = shiftService.execute(exec, SYSTEM);
        assertEquals(1, done.getBatch().getScannedCount());
        assertEquals(1, done.getBatch().getShiftedCount());
        assertEquals(LocalDateTime.of(monday1.plusDays(1), java.time.LocalTime.of(9, 0)),
                dispatchService.requireByNo(d1.getDispatchNo()).getPlanStart());
        // d2 没动，下批再扫仍命中
        assertEquals(LocalDateTime.of(monday2, java.time.LocalTime.of(9, 0)),
                dispatchService.requireByNo(d2.getDispatchNo()).getPlanStart());
        assertEquals(1, shiftService.preview(YEAR, SYSTEM).getHitCount());
    }

    // ---- 权限与空日历 ----

    @Test
    void 没有日历或没发布_预览执行都拒() {
        BusinessException noCalendar = assertThrows(BusinessException.class,
                () -> shiftService.preview(2098, SYSTEM));
        assertTrue(noCalendar.getMessage().contains("还没有假期日历"));

        calendarService.importCalendar(importReq(2098, day("2098-10-01", "REST", "国庆节")), SYSTEM);
        BusinessException noEffective = assertThrows(BusinessException.class,
                () -> shiftService.preview(2098, SYSTEM));
        assertTrue(noEffective.getMessage().contains("还没有生效版本"));

        ShiftExecuteRequest exec = new ShiftExecuteRequest();
        exec.setYear(2098);
        assertThrows(BusinessException.class, () -> shiftService.execute(exec, SYSTEM));
    }

    @Test
    void 非系统角色维护日历与顺延都被拒() {
        Viewer finance = Viewer.finance("finance1");
        BusinessException importDenied = assertThrows(BusinessException.class,
                () -> calendarService.importCalendar(importReq(YEAR,
                        day(YEAR + "-10-01", "REST", "国庆节")), finance));
        assertTrue(importDenied.getMessage().contains("系统角色"));

        importAndPublish(YEAR, day(YEAR + "-10-01", "REST", "国庆节"));
        long draftId = calendarService.importCalendar(importReq(YEAR,
                day(YEAR + "-10-02", "REST", "国庆节")), SYSTEM).getVersion().getId();
        assertThrows(BusinessException.class, () -> calendarService.publish(draftId, finance));
        assertThrows(BusinessException.class, () -> shiftService.preview(YEAR, finance));
        ShiftExecuteRequest exec = new ShiftExecuteRequest();
        exec.setYear(YEAR);
        assertThrows(BusinessException.class, () -> shiftService.execute(exec, finance));
        assertThrows(BusinessException.class, () -> shiftService.listBatches(finance));
        assertThrows(BusinessException.class,
                () -> calendarService.importCalendar(importReq(YEAR,
                        day(YEAR + "-10-03", "REST", "国庆节")), Viewer.sales("sales1")));
    }
}
