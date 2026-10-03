package com.evops.holiday.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.holiday.dto.CalendarImportRequest;
import com.evops.holiday.dto.ImportResultView;
import com.evops.holiday.entity.HolidayCalendar;
import com.evops.holiday.entity.HolidayCalendarVersion;
import com.evops.holiday.service.HolidayCalendarService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 假期日历：翻查（所有登录角色）；按年导入与发布（仅系统角色，Viewer.requireSystemAdmin 兜底）。
 */
@RestController
@RequestMapping("/api/holiday-calendars")
public class HolidayCalendarController {

    private final HolidayCalendarService calendarService;

    public HolidayCalendarController(HolidayCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    private static Viewer viewer() {
        Viewer v = Viewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    /** 日历翻查（?year=2026 可按年过滤）。 */
    @GetMapping
    public ApiResponse<List<HolidayCalendar>> list(@RequestParam(required = false) Integer year) {
        return ApiResponse.ok(calendarService.list(year));
    }

    /** 版本链翻查（版本即操作记录：经手人/发布时刻/导入说明）。 */
    @GetMapping("/{calendarId}/versions")
    public ApiResponse<List<HolidayCalendarVersion>> versions(@PathVariable long calendarId) {
        return ApiResponse.ok(calendarService.versionsOf(calendarId));
    }

    /** 版本详情：全量日期（休息日/调休上班日）+ 与当前生效版的差异比对。 */
    @GetMapping("/versions/{versionId}")
    public ApiResponse<ImportResultView> versionDetail(@PathVariable long versionId) {
        return ApiResponse.ok(calendarService.versionDetail(versionId));
    }

    /**
     * 按年导入（整表替换式）：该年已有编制中草稿则整表覆盖草稿，否则另起新草稿版；
     * 返回草稿版本与当前生效版的差异比对（新增/移除/变更），确认后再发布覆盖。仅系统角色。
     */
    @PostMapping("/import")
    public ApiResponse<ImportResultView> importCalendar(@Valid @RequestBody CalendarImportRequest request) {
        return ApiResponse.ok(calendarService.importCalendar(request, viewer()));
    }

    /** 发布：即时生效，旧生效版自动 SUPERSEDED。仅系统角色。 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<HolidayCalendarVersion> publish(@PathVariable long versionId) {
        return ApiResponse.ok(calendarService.publish(versionId, viewer()));
    }
}
