package com.evops.holiday.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.holiday.dto.HolidayDaysConfigureRequest;
import com.evops.holiday.dto.HolidayImportDiffView;
import com.evops.holiday.dto.HolidayImportRequest;
import com.evops.holiday.dto.HolidayPublishRequest;
import com.evops.holiday.dto.ShiftPreviewRequest;
import com.evops.holiday.dto.ShiftPreviewView;
import com.evops.holiday.dto.WorkingDayView;
import com.evops.holiday.entity.HolidayCalendar;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.entity.HolidayDay;
import com.evops.holiday.entity.HolidayShiftBatch;
import com.evops.holiday.entity.HolidayShiftRecord;
import com.evops.holiday.service.HolidayCalendarService;
import com.evops.holiday.service.HolidayShiftService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假期日历与档期顺延：
 * - 年历/版本/工作日判定所有登录用户可查；
 * - 另起草稿、配置、发布、按年导入、顺延预览/确认只认运营/系统（Viewer.requireCalendarAdmin）。
 */
@RestController
@RequestMapping("/api/holidays")
public class HolidayController {

    private final HolidayCalendarService calendarService;
    private final HolidayShiftService shiftService;

    public HolidayController(HolidayCalendarService calendarService, HolidayShiftService shiftService) {
        this.calendarService = calendarService;
        this.shiftService = shiftService;
    }

    private static Viewer viewer() {
        Viewer v = Viewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    // ---- 年度日历翻查 ----

    /** 某年日历：主档 + 当前生效版本 + 全量条目（含 REST/WORK 标记）；未建档则主档为空。 */
    @GetMapping("/years/{year}")
    public ApiResponse<Map<String, Object>> yearCalendar(@PathVariable int year) {
        Map<String, Object> view = new LinkedHashMap<>();
        HolidayCalendar cal = calendarService.findByYear(year);
        view.put("calendar", cal);
        HolidayCalendarVersion effective = calendarService.currentEffective(year);
        view.put("version", effective);
        view.put("days", effective == null ? java.util.Collections.emptyList()
                : calendarService.itemsOf(effective.getId()));
        return ApiResponse.ok(view);
    }

    @GetMapping("/years/{year}/versions")
    public ApiResponse<List<HolidayCalendarVersion>> versions(@PathVariable int year) {
        return ApiResponse.ok(calendarService.listVersions(year));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<Map<String, Object>> versionDetail(@PathVariable Long versionId) {
        HolidayCalendarVersion v = calendarService.requireVersion(versionId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", v);
        view.put("days", calendarService.itemsOf(v.getId()));
        return ApiResponse.ok(view);
    }

    /** 工作日判定：?date=yyyy-MM-dd（休息日/调休上班日/普通工作日/普通周末，带依据版本）。 */
    @GetMapping("/working-day")
    public ApiResponse<WorkingDayView> workingDay(@RequestParam String date) {
        LocalDate d = parseDate(date);
        return ApiResponse.ok(calendarService.inspect(d));
    }

    // ---- 手工草稿流（运营/系统） ----

    @PostMapping("/years/{year}/versions")
    public ApiResponse<HolidayCalendarVersion> newDraft(@PathVariable int year) {
        return ApiResponse.ok(calendarService.newDraft(year, viewer()));
    }

    @PutMapping("/versions/{versionId}/days")
    public ApiResponse<HolidayCalendarVersion> configure(@PathVariable Long versionId,
                                                         @Valid @RequestBody HolidayDaysConfigureRequest request) {
        return ApiResponse.ok(calendarService.configure(versionId, request.getDays(), viewer()));
    }

    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<HolidayCalendarVersion> publish(@PathVariable Long versionId,
                                                       @RequestBody(required = false) HolidayPublishRequest request) {
        return ApiResponse.ok(calendarService.publish(versionId,
                request == null ? null : request.getChangeSummary(), viewer()));
    }

    // ---- 按年导入：先比差异，确认发布（运营/系统） ----

    /** 导入差异比对（不落库）：返回新增/删除/REST⇄WORK 类型变化/不变明细。 */
    @PostMapping("/years/{year}/import/preview")
    public ApiResponse<HolidayImportDiffView> importPreview(@PathVariable int year,
                                                            @Valid @RequestBody HolidayImportRequest request) {
        return ApiResponse.ok(calendarService.importPreview(year, request.getDays()));
    }

    /** 确认导入：整表替换发布新版，旧版与未发布草稿自动取代。 */
    @PostMapping("/years/{year}/import/confirm")
    public ApiResponse<HolidayCalendarVersion> importConfirm(@PathVariable int year,
                                                             @Valid @RequestBody HolidayImportRequest request) {
        return ApiResponse.ok(calendarService.importConfirm(year, request.getDays(),
                request.getChangeSummary(), viewer()));
    }

    // ---- 档期顺延：预览 → 确认批量执行（运营/系统） ----

    @PostMapping("/shifts/preview")
    public ApiResponse<ShiftPreviewView> shiftPreview(@RequestBody ShiftPreviewRequest request) {
        return ApiResponse.ok(shiftService.preview(request, viewer()));
    }

    @PostMapping("/shifts/{batchNo}/confirm")
    public ApiResponse<HolidayShiftBatch> shiftConfirm(@PathVariable String batchNo) {
        return ApiResponse.ok(shiftService.confirm(batchNo, viewer()));
    }

    @GetMapping("/shifts")
    public ApiResponse<List<HolidayShiftBatch>> batches(@RequestParam(required = false) String status) {
        return ApiResponse.ok(shiftService.listBatches(status));
    }

    @GetMapping("/shifts/{batchNo}")
    public ApiResponse<Map<String, Object>> batchDetail(@PathVariable String batchNo) {
        HolidayShiftBatch batch = shiftService.requireBatch(batchNo);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batch", batch);
        view.put("records", shiftService.recordsOf(batch.getId()));
        return ApiResponse.ok(view);
    }

    /** 按上刊单号翻顺延痕迹（原档期/新档期/批次/结果）。 */
    @GetMapping("/shifts/records")
    public ApiResponse<List<HolidayShiftRecord>> recordsByOrder(@RequestParam String orderNo) {
        return ApiResponse.ok(shiftService.recordsOfOrder(orderNo));
    }

    private LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date.trim());
        } catch (Exception e) {
            throw new BusinessException("日期格式应为 yyyy-MM-dd，例如 2026-10-02");
        }
    }
}
