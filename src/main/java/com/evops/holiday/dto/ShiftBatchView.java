package com.evops.holiday.dto;

import com.evops.holiday.entity.HolidayShiftBatch;
import com.evops.holiday.entity.HolidayShiftRecord;
import lombok.Data;

import java.util.List;

/** 顺延批次详情：批次头 + 逐单记录（原档期/新档期/成败原因全留底）。 */
@Data
public class ShiftBatchView {
    private HolidayShiftBatch batch;
    private List<HolidayShiftRecord> records;
}
