package com.evops.holiday;

import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.credit.entity.CreditOccupation;
import com.evops.credit.mapper.CreditOccupationMapper;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.service.CustomerService;
import com.evops.holiday.dto.HolidayDaySpec;
import com.evops.holiday.dto.HolidayImportDiffView;
import com.evops.holiday.dto.ShiftPreviewRequest;
import com.evops.holiday.dto.ShiftPreviewView;
import com.evops.holiday.dto.WorkingDayView;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.entity.HolidayShiftBatch;
import com.evops.holiday.entity.HolidayShiftRecord;
import com.evops.holiday.service.HolidayCalendarService;
import com.evops.holiday.service.HolidayShiftService;
import com.evops.install.dto.ArriveRequest;
import com.evops.install.dto.RescheduleRequest;
import com.evops.install.dto.TeamCreateRequest;
import com.evops.install.entity.InstallNotice;
import com.evops.install.entity.InstallOrder;
import com.evops.install.entity.InstallTeam;
import com.evops.install.mapper.InstallNoticeMapper;
import com.evops.install.mapper.InstallOrderMapper;
import com.evops.install.mapper.InstallTeamMapper;
import com.evops.install.service.InstallDispatchService;
import com.evops.install.service.InstallTeamService;
import org.junit.jupiter.api.BeforeEach;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.OrderEvent;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.SiteService;
import com.evops.ratecard.dto.QuoteDetailView;
import com.evops.ratecard.dto.QuoteLineRequest;
import com.evops.ratecard.dto.QuoteSubmitRequest;
import com.evops.ratecard.dto.RateCardCreateRequest;
import com.evops.ratecard.dto.SitePriceSpec;
import com.evops.ratecard.dto.TierSpec;
import com.evops.ratecard.dto.VersionActionRequest;
import com.evops.ratecard.dto.VersionConfigureRequest;
import com.evops.ratecard.service.QuoteService;
import com.evops.ratecard.service.RateCardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F11 假期日历与档期自动顺延端到端（真实 H2 + 真实事务，无 Shiro，显式 Viewer）。
 * 类级 {@link Transactional} 每用例回滚。覆盖：按年 REST/WORK 维护与工作日判定、覆盖导入差异、
 * 休息日顺延/调休上班日锚定、预览确认批次、撞档跳过、原档期痕迹、安装派单联动改期/转待匹配/到场拦截、
 * 授信占用期间迁移、跨年顺延、越权拦截。
 */
@SpringBootTest
@Transactional
class HolidayCalendarFlowTest {

    @Autowired private HolidayCalendarService calendarService;
    @Autowired private HolidayShiftService shiftService;
    @Autowired private OrderService orderService;
    @Autowired private SiteService siteService;
    @Autowired private InstallTeamService teamService;
    @Autowired private InstallTeamMapper teamMapper;
    @Autowired private InstallDispatchService dispatchService;
    @Autowired private InstallNoticeMapper noticeMapper;
    @Autowired private CustomerService customerService;
    @Autowired private RateCardService rateCardService;
    @Autowired private QuoteService quoteService;
    @Autowired private CreditOccupationMapper occupationMapper;

    private final Viewer ops = Viewer.ops("ops1");
    private final Viewer sales = Viewer.sales("sales1");

    /**
     * 非事务的安装测试类会把安装队/派单提交进共享内存库。每个用例先停用所有在册队（本用例事务结束回滚），
     * 再各自 {@link #team()} 建全新队，保证假期单自动匹配只命中本用例的队，匹配结果确定。
     */
    @BeforeEach
    void disableForeignTeams() {
        for (InstallTeam t : teamMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InstallTeam>()
                        .eq(InstallTeam::getStatus, com.evops.install.InstallEnums.TEAM_ACTIVE))) {
            InstallTeam patch = new InstallTeam();
            patch.setId(t.getId());
            patch.setStatus(com.evops.install.InstallEnums.TEAM_DISABLED);
            teamMapper.updateById(patch);
        }
    }

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    // ---- 造数 ----

    private HolidayDaySpec day(String date, String type, String name) {
        HolidayDaySpec s = new HolidayDaySpec();
        s.setDate(LocalDate.parse(date));
        s.setType(type);
        s.setName(name);
        return s;
    }

    /** 导入一套 2026 国庆安排：10-01~10-07 休；09-27（日）、10-10（六）调休上班。 */
    private HolidayCalendarVersion import2026() {
        List<HolidayDaySpec> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            days.add(day(String.format("2026-10-%02d", d), "REST", "国庆节"));
        }
        days.add(day("2026-09-27", "WORK", "国庆调休上班"));
        days.add(day("2026-10-10", "WORK", "国庆调休上班"));
        return calendarService.importConfirm(2026, days, "2026 国庆安排", ops);
    }

    private Site site() {
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("HS"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝北");
        sr.setBusinessDistrict("假期测试商圈");
        sr.setOperatorName("tester");
        return siteService.register(sr);
    }

    private String team() {
        TeamCreateRequest t = new TeamCreateRequest();
        t.setTeamName(uniqueNo("假期队"));
        t.setLeaderName("李队");
        t.setContact("13800000000");
        t.setOperatorName("dispatcher");
        return teamService.create(t).getTeamNo();
    }

    private PostingOrder post(Site site, String creative, LocalDate occurDate) {
        return post(site, creative, occurDate, null, "tester");
    }

    private PostingOrder post(Site site, String creative, LocalDate occurDate, String quoteNo) {
        return post(site, creative, occurDate, quoteNo, "finance1");
    }

    private PostingOrder post(Site site, String creative, LocalDate occurDate, String quoteNo, String operator) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setCreativeName(creative);
        req.setIndustryCategory("快消品");
        req.setOccurDate(occurDate);
        req.setQuoteNo(quoteNo);
        req.setOperatorName(operator);
        return orderService.createPosting(req);
    }

    private ShiftPreviewView preview(LocalDate from, LocalDate to) {
        return preview(from, to, null);
    }

    /** 限定点位预览：类内用例共享内存库，限本用例自建点位避免其他测试遗留挂起单干扰计数。 */
    private ShiftPreviewView preview(LocalDate from, LocalDate to, Site site) {
        ShiftPreviewRequest req = new ShiftPreviewRequest();
        req.setFrom(from);
        req.setTo(to);
        req.setSiteNo(site == null ? null : site.getSiteNo());
        req.setReason("国庆假期调整");
        return shiftService.preview(req, ops);
    }

    private MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return new MockMultipartFile("photos", "p.png", "image/png", bos.toByteArray());
    }

    // ---- 用例 ----

    @Test
    void import_takes_effect_and_working_day_inspection() {
        import2026();
        // REST：本来是工作日的周五也休
        WorkingDayView fri = calendarService.inspect(LocalDate.of(2026, 10, 2));
        assertFalse(fri.getWorking());
        assertEquals("REST", fri.getBasis());
        assertEquals(1, fri.getCalendarVersionNo());
        // WORK：本来是周末的周六也上班
        WorkingDayView sat = calendarService.inspect(LocalDate.of(2026, 10, 10));
        assertTrue(sat.getWorking());
        assertEquals("WORK", sat.getBasis());
        // 无条目：普通周末休、普通工作日工作
        assertFalse(calendarService.inspect(LocalDate.of(2026, 9, 19)).getWorking()); // 周六
        assertTrue(calendarService.inspect(LocalDate.of(2026, 9, 18)).getWorking());  // 周五
        // 没发布年历的年份回退自然周：2030-01-07 周一工作、2030-01-05 周六休息
        assertTrue(calendarService.inspect(LocalDate.of(2030, 1, 7)).getWorking());
        assertFalse(calendarService.inspect(LocalDate.of(2030, 1, 5)).getWorking());
        assertEquals(9, calendarService.itemsOf(calendarService.currentEffective(2026).getId()).size());
    }

    @Test
    void reimport_diff_and_supersede() {
        HolidayCalendarVersion v1 = import2026();
        // 第二次导入：删掉 10-07 休息、把 10-10 调休上班改成休息、新增 10-08 休息，其余不变
        List<HolidayDaySpec> days = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            days.add(day(String.format("2026-10-%02d", d), "REST", "国庆节"));
        }
        days.add(day("2026-09-27", "WORK", "国庆调休上班"));
        days.add(day("2026-10-10", "REST", "国庆调休改休"));
        days.add(day("2026-10-08", "REST", "额外休"));

        HolidayImportDiffView diff = calendarService.importPreview(2026, days);
        assertEquals(1, diff.getAddedCount());
        assertEquals(1, diff.getRemovedCount());
        assertEquals(1, diff.getTypeChangedCount());
        assertEquals(7, diff.getUnchangedCount());

        HolidayCalendarVersion v2 = calendarService.importConfirm(2026, days, "二次调整", ops);
        assertEquals(2, v2.getVersionNo());
        assertEquals(HolidayEnums.VERSION_SUPERSEDED,
                calendarService.requireVersion(v1.getId()).getStatus());
        assertEquals(HolidayEnums.VERSION_EFFECTIVE,
                calendarService.currentEffective(2026).getStatus());
        assertEquals(2, calendarService.listVersions(2026).size());
    }

    @Test
    void import_rejects_wrong_year_and_duplicate_and_bad_type() {
        BusinessException e1 = assertThrows(BusinessException.class, () ->
                calendarService.importConfirm(2026,
                        Arrays.asList(day("2025-10-01", "REST", "跨年条目")), "x", ops));
        assertTrue(e1.getMessage().contains("不属于 2026 年"));

        BusinessException e2 = assertThrows(BusinessException.class, () ->
                calendarService.importConfirm(2026, Arrays.asList(
                        day("2026-10-01", "REST", "休"),
                        day("2026-10-01", "WORK", "同一天又上班")), "x", ops));
        assertTrue(e2.getMessage().contains("重复配置"));

        BusinessException e3 = assertThrows(BusinessException.class, () ->
                calendarService.importConfirm(2026,
                        Arrays.asList(day("2026-10-01", "HOLIDAY", "乱填")), "x", ops));
        assertTrue(e3.getMessage().contains("类型非法"));
    }

    @Test
    void rest_day_posting_shifts_to_next_working_day_and_week_recomputed() {
        import2026();
        team();
        Site site = site();
        // 10-02 周五（国庆休）→ 越过周末 10-03/04 → 首个工作日是调休上班 10-08（周四，国庆 10-01~07 连休）
        PostingOrder o = post(site, "国庆画面", LocalDate.of(2026, 10, 2));

        ShiftPreviewView pv = preview(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), site);
        assertEquals(1, pv.getHitCount());
        ShiftPreviewView.ShiftRow row = pv.getShifts().get(0);
        assertEquals(LocalDate.of(2026, 10, 8), row.getNewOccurDate());
        assertEquals(LocalDate.of(2026, 10, 5), row.getNewWeekStart());
        assertEquals(LocalDate.of(2026, 10, 11), row.getNewWeekEnd());

        HolidayShiftBatch batch = shiftService.confirm(pv.getBatchNo(), ops);
        assertEquals(1, batch.getShiftedCount());
        assertEquals(0, batch.getSkippedCount());

        PostingOrder moved = orderService.requireByNo(o.getOrderNo());
        assertEquals(LocalDate.of(2026, 10, 8), moved.getOccurDate());
        assertEquals(LocalDate.of(2026, 10, 5), moved.getWeekStart());
        assertEquals(LocalDate.of(2026, 10, 11), moved.getWeekEnd());
        assertEquals("2026-10", moved.getBizPeriod());
        // 原档期痕迹只固化一次
        assertEquals(LocalDate.of(2026, 10, 2), moved.getOriginOccurDate());
        assertEquals(LocalDate.of(2026, 9, 28), moved.getOriginWeekStart());
        assertEquals(1, moved.getHolidayShiftCount());
        // 底账事件留底
        List<OrderEvent> events = orderService.events(moved.getId());
        assertTrue(events.stream().anyMatch(e -> "HOLIDAY_SHIFT".equals(e.getEventType())));
        // 再预览同范围，已挪走的单不命中
        assertEquals(0, preview(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), site).getHitCount());
        // 批次不能重复确认
        assertThrows(BusinessException.class, () -> shiftService.confirm(pv.getBatchNo(), ops));
    }

    @Test
    void makeup_workday_anchors_shift_and_is_not_hit() {
        import2026();
        team();
        // 09-26 周六（普通周末休）→ 下一工作日是 09-27 周日调休上班，停在调休日不再往后推
        Site site1 = site();
        PostingOrder sat = post(site1, "调休锚定", LocalDate.of(2026, 9, 26));
        ShiftPreviewView pv = preview(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27), site1);
        assertEquals(1, pv.getHitCount());
        assertEquals(LocalDate.of(2026, 9, 27), pv.getShifts().get(0).getNewOccurDate());
        shiftService.confirm(pv.getBatchNo(), ops);
        assertEquals(LocalDate.of(2026, 9, 27), orderService.requireByNo(sat.getOrderNo()).getOccurDate());

        // 发生日就是调休上班日（10-10 周六 WORK）的单不命中
        Site site2 = site();
        PostingOrder workdayOrder = post(site2, "调休上班日的单", LocalDate.of(2026, 10, 10));
        ShiftPreviewView pv2 = preview(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10), site2);
        assertEquals(0, pv2.getHitCount());
        assertEquals(LocalDate.of(2026, 10, 10), orderService.requireByNo(workdayOrder.getOrderNo()).getOccurDate());
    }

    @Test
    void target_week_conflict_previewed_and_skipped_until_slot_freed() {
        import2026();
        team();
        Site s = site();
        PostingOrder holidayOrder = post(s, "假期单", LocalDate.of(2026, 10, 2));
        // 占位单：挂在目标周 10-05~10-11（10-09 周五，普通工作日）
        PostingOrder blocker = post(s, "占位单", LocalDate.of(2026, 10, 9));

        ShiftPreviewView pv = preview(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), s);
        assertEquals(1, pv.getHitCount());
        assertEquals(blocker.getOrderNo(), pv.getShifts().get(0).getConflictOrderNo());

        HolidayShiftBatch batch = shiftService.confirm(pv.getBatchNo(), ops);
        assertEquals(0, batch.getShiftedCount());
        assertEquals(1, batch.getSkippedCount());
        HolidayShiftRecord rec = shiftService.recordsOf(batch.getId()).get(0);
        assertEquals(HolidayEnums.RECORD_SKIPPED, rec.getStatus());
        assertNotNull(rec.getSkipReason());
        // 单据没动
        assertEquals(LocalDate.of(2026, 10, 2), orderService.requireByNo(holidayOrder.getOrderNo()).getOccurDate());

        // 占位单落架释放档期后，重新预览确认即可顺延成功
        ActionRequest td = new ActionRequest();
        td.setOccurDate(LocalDate.of(2026, 10, 9));
        td.setOperatorName("tester");
        orderService.takeDown(blocker.getOrderNo(), td);
        ShiftPreviewView pv2 = preview(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), s);
        assertNull(pv2.getShifts().get(0).getConflictOrderNo());
        shiftService.confirm(pv2.getBatchNo(), ops);
        assertEquals(LocalDate.of(2026, 10, 8),
                orderService.requireByNo(holidayOrder.getOrderNo()).getOccurDate());
    }

    @Test
    void install_dispatch_rescheduled_and_team_notified() {
        import2026();
        String teamNo = team();
        Site s = site();
        PostingOrder o = post(s, "联动改期", LocalDate.of(2026, 10, 2));
        InstallOrder dispatch = dispatchService.getByOrderNo(o.getOrderNo());
        assertEquals("ASSIGNED", dispatch.getStatus());
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0), dispatch.getPlanStart());

        ShiftPreviewView pv = preview(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), s);
        shiftService.confirm(pv.getBatchNo(), ops);

        InstallOrder moved = dispatchService.getByOrderNo(o.getOrderNo());
        assertEquals("ASSIGNED", moved.getStatus());
        assertEquals(teamNo, moved.getTeamNo());
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 0), moved.getPlanStart());
        assertEquals(LocalDateTime.of(2026, 10, 8, 13, 0), moved.getPlanEnd());
        assertEquals(1, moved.getRescheduleCount());
        // 通知到队
        List<InstallNotice> notices = noticeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InstallNotice>()
                        .eq(InstallNotice::getDispatchId, moved.getId()));
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).getReason().contains("假期顺延"));
        // 上刊单安装状态镜像
        assertEquals("ASSIGNED", orderService.requireByNo(o.getOrderNo()).getInstallStatus());
        // 批次记录联动结果
        HolidayShiftRecord rec = shiftService.recordsOfOrder(o.getOrderNo()).stream()
                .filter(r -> HolidayEnums.RECORD_SHIFTED.equals(r.getStatus())).findFirst().orElse(null);
        assertNotNull(rec);
        assertEquals(com.evops.install.InstallEnums.INSTALL_RESCHEDULED, rec.getInstallAction());
    }

    @Test
    void install_team_busy_turns_dispatch_to_pending() {
        import2026();
        String teamNo = team();
        // 别点位列一张普通日的单，把它的派单改期到顺延目标窗口 10-08 09:00~13:00 占住该队
        Site other = site();
        PostingOrder otherOrder = post(other, "占队单", LocalDate.of(2026, 9, 15));
        InstallOrder otherDispatch = dispatchService.getByOrderNo(otherOrder.getOrderNo());
        RescheduleRequest rr = new RescheduleRequest();
        rr.setNewStart(LocalDateTime.of(2026, 10, 8, 9, 0));
        rr.setNewEnd(LocalDateTime.of(2026, 10, 8, 13, 0));
        rr.setReason("客户改期");
        rr.setOperatorName("dispatcher");
        dispatchService.reschedule(otherDispatch.getDispatchNo(), rr);

        // 假期单顺延 → 队新窗口被占 → 转待匹配并释放该队
        Site s = site();
        PostingOrder o = post(s, "撞队单", LocalDate.of(2026, 10, 2));
        ShiftPreviewView pv = preview(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), s);
        shiftService.confirm(pv.getBatchNo(), ops);

        InstallOrder d = dispatchService.getByOrderNo(o.getOrderNo());
        assertEquals("PENDING", d.getStatus());
        assertNull(d.getTeamId());
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 0), d.getPlanStart());
        HolidayShiftRecord rec = shiftService.recordsOfOrder(o.getOrderNo()).stream()
                .filter(r -> HolidayEnums.RECORD_SHIFTED.equals(r.getStatus())).findFirst().orElse(null);
        assertEquals(com.evops.install.InstallEnums.INSTALL_TO_PENDING, rec.getInstallAction());
        // 通知仍记在原队名下
        InstallNotice last = noticeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InstallNotice>()
                        .eq(InstallNotice::getDispatchId, d.getId())
                        .orderByDesc(InstallNotice::getSeq)).get(0);
        assertEquals(teamNo, last.getTeamNo());
        // 原队可正常接活：待匹配补扫时占队单之外不再有冲突（手工匹配校验通过）
        com.evops.install.dto.ManualMatchRequest mm = new com.evops.install.dto.ManualMatchRequest();
        mm.setTeamNo(teamNo);
        mm.setPlanStart(LocalDateTime.of(2026, 10, 9, 9, 0));
        mm.setPlanEnd(LocalDateTime.of(2026, 10, 9, 13, 0));
        mm.setOperatorName("dispatcher");
        assertEquals("ASSIGNED", dispatchService.manualMatch(d.getDispatchNo(), mm).getStatus());
    }

    @Test
    void arrived_dispatch_blocked_and_closed_orders_not_hit() throws Exception {
        import2026();
        team();
        Site s = site();
        PostingOrder o = post(s, "已到场单", LocalDate.of(2026, 10, 2));
        InstallOrder dispatch = dispatchService.getByOrderNo(o.getOrderNo());
        ArriveRequest ar = new ArriveRequest();
        ar.setOperatorName("installer1");
        dispatchService.arrive(dispatch.getDispatchNo(), ar,
                new MockMultipartFile[] { photo() });

        ShiftPreviewView pv = preview(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), s);
        assertEquals(0, pv.getHitCount());
        assertEquals(1, pv.getBlockedCount());
        assertTrue(pv.getBlocked().get(0).getBlockReason().contains("已到场打卡"));
        // 确认是空批，单据原封不动
        shiftService.confirm(pv.getBatchNo(), ops);
        assertEquals(LocalDate.of(2026, 10, 2), orderService.requireByNo(o.getOrderNo()).getOccurDate());

        // 已落架单也不命中
        Site s2 = site();
        PostingOrder closed = post(s2, "已落架", LocalDate.of(2026, 10, 2));
        ActionRequest td = new ActionRequest();
        td.setOccurDate(LocalDate.of(2026, 10, 2));
        td.setOperatorName("tester");
        orderService.takeDown(closed.getOrderNo(), td);
        ShiftPreviewView pv2 = preview(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), s2);
        // 已落架单不命中（限定 s2 点位，本点位无到场拦截单）
        assertEquals(0, pv2.getHitCount());
        assertEquals(0, pv2.getBlockedCount());
    }

    @Test
    void credit_occupation_period_moves_with_slot_across_month() {
        // 11-30（周一）、12-01（周二）连休，12-02 周三上班：跨月顺延 2026-11 → 2026-12
        List<HolidayDaySpec> days = Arrays.asList(
                day("2026-11-30", "REST", "临时连休"),
                day("2026-12-01", "REST", "临时连休"));
        calendarService.importConfirm(2026, days, "跨月安排", ops);
        team();
        Site s = setupRatedSite();
        String customerName = uniqueNo("假期授信客户");
        CustomerCreateRequest cr = new CustomerCreateRequest();
        cr.setCustomerName(customerName);
        cr.setIndustryCategory("快消品");
        cr.setCreditLimit(new BigDecimal("100000"));
        customerService.create(cr, Viewer.finance("finance1"));
        QuoteDetailView quote = quote(s, customerName);

        PostingOrder o = post(s, "授信跨月单", LocalDate.of(2026, 11, 30), quote.getQuote().getQuoteNo());
        CreditOccupation occ = occupationMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CreditOccupation>()
                        .eq(CreditOccupation::getOrderId, o.getId()));
        assertEquals("2026-11", occ.getBizPeriod());

        ShiftPreviewView pv = preview(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 1), s);
        assertEquals(LocalDate.of(2026, 12, 2), pv.getShifts().get(0).getNewOccurDate());
        shiftService.confirm(pv.getBatchNo(), ops);

        CreditOccupation moved = occupationMapper.selectById(occ.getId());
        assertEquals("ACTIVE", moved.getStatus());
        assertEquals("2026-12", moved.getBizPeriod());
        assertEquals(occ.getAmount(), moved.getAmount());
        assertEquals("2026-12", orderService.requireByNo(o.getOrderNo()).getBizPeriod());
    }

    @Test
    void next_working_day_crosses_year_boundary() {
        // 2026-12-31（周四）休；2027 元旦 01-01~03 休；01-04 周一上班
        calendarService.importConfirm(2026,
                Arrays.asList(day("2026-12-31", "REST", "年底连休")), "年底", ops);
        calendarService.importConfirm(2027, Arrays.asList(
                day("2027-01-01", "REST", "元旦"),
                day("2027-01-02", "REST", "元旦"),
                day("2027-01-03", "REST", "元旦")), "2027 元旦", ops);
        team();
        Site s = site();
        PostingOrder o = post(s, "跨年单", LocalDate.of(2026, 12, 31));
        ShiftPreviewView pv = preview(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 31), s);
        assertEquals(LocalDate.of(2027, 1, 4), pv.getShifts().get(0).getNewOccurDate());
        shiftService.confirm(pv.getBatchNo(), ops);
        assertEquals(LocalDate.of(2027, 1, 4), orderService.requireByNo(o.getOrderNo()).getOccurDate());
    }

    @Test
    void sales_cannot_import_or_shift() {
        assertThrows(BusinessException.class, () -> calendarService.importConfirm(2026,
                Arrays.asList(day("2026-10-01", "REST", "休")), "越权", sales));
        ShiftPreviewRequest req = new ShiftPreviewRequest();
        req.setFrom(LocalDate.of(2026, 10, 1));
        req.setTo(LocalDate.of(2026, 10, 7));
        assertThrows(BusinessException.class, () -> shiftService.preview(req, sales));
    }

    // ---- 授信造数（刊例 2025 年，报价按过去年；上刊发生日独立取 2026 假期） ----

    private Site setupRatedSite() {
        Site s = site();
        RateCardCreateRequest cr = new RateCardCreateRequest();
        cr.setRateYear(2025);
        cr.setCardName(uniqueNo("假期刊例"));
        cr.setOperatorName("tester");
        rateCardService.createCard(cr);
        com.evops.ratecard.entity.RateCard card = rateCardService.findByYear(2025);
        long v1 = rateCardService.listVersions(card.getId()).get(0).getId();
        TierSpec tier = new TierSpec();
        tier.setMinWeeks(1);
        tier.setMaxWeeks(null);
        tier.setDiscountRate(new BigDecimal("1.0000"));
        SitePriceSpec sp = new SitePriceSpec();
        sp.setSiteNo(s.getSiteNo());
        sp.setListPrice(new BigDecimal("1000"));
        VersionConfigureRequest cfg = new VersionConfigureRequest();
        cfg.setTiers(new ArrayList<>(Arrays.asList(tier)));
        cfg.setSitePrices(new ArrayList<>(Arrays.asList(sp)));
        cfg.setOperatorName("tester");
        rateCardService.configure(v1, cfg);
        VersionActionRequest a = new VersionActionRequest();
        a.setOperatorName("tester");
        rateCardService.publish(v1, a);
        return s;
    }

    private QuoteDetailView quote(Site site, String customerName) {
        QuoteLineRequest line = new QuoteLineRequest();
        line.setSiteNo(site.getSiteNo());
        line.setWeeks(1);
        QuoteSubmitRequest req = new QuoteSubmitRequest();
        req.setCustomerName(customerName);
        req.setSubmitDate(LocalDate.of(2025, 6, 1));
        req.setLines(new ArrayList<>(Arrays.asList(line)));
        req.setOperatorName("tester");
        return quoteService.submit(req);
    }
}
