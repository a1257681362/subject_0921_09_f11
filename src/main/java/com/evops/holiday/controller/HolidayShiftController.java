package com.evops.holiday.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.holiday.dto.ShiftBatchView;
import com.evops.holiday.dto.ShiftExecuteRequest;
import com.evops.holiday.dto.ShiftPreviewView;
import com.evops.holiday.entity.HolidayShiftBatch;
import com.evops.holiday.service.HolidayShiftService;
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
 * 假期顺延：先出预览、确认后批量执行、批次留痕翻查（均仅系统角色，Viewer.requireSystemAdmin 兜底）。
 */
@RestController
@RequestMapping("/api/holiday-shifts")
public class HolidayShiftController {

    private final HolidayShiftService shiftService;

    public HolidayShiftController(HolidayShiftService shiftService) {
        this.shiftService = shiftService;
    }

    private static Viewer viewer() {
        Viewer v = Viewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    /** 顺延预览：计划落在休息日的在途派单，逐单给出新档期与可挪性预检。仅系统角色。 */
    @GetMapping("/preview")
    public ApiResponse<ShiftPreviewView> preview(@RequestParam int year) {
        return ApiResponse.ok(shiftService.preview(year, viewer()));
    }

    /** 确认批量顺延：按某年生效日历执行；可指定派单子集。仅系统角色。 */
    @PostMapping
    public ApiResponse<ShiftBatchView> execute(@Valid @RequestBody ShiftExecuteRequest request) {
        return ApiResponse.ok(shiftService.execute(request, viewer()));
    }

    /** 顺延批次翻查。仅系统角色。 */
    @GetMapping("/batches")
    public ApiResponse<List<HolidayShiftBatch>> batches() {
        return ApiResponse.ok(shiftService.listBatches(viewer()));
    }

    /** 批次详情（含逐单记录：原档期/新档期/成败原因）。仅系统角色。 */
    @GetMapping("/batches/{batchNo}")
    public ApiResponse<ShiftBatchView> batchDetail(@PathVariable String batchNo) {
        return ApiResponse.ok(shiftService.batchDetail(batchNo, viewer()));
    }
}
