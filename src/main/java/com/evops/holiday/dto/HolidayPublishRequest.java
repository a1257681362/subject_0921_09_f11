package com.evops.holiday.dto;

import lombok.Data;

/** 发布手工草稿版本（留操作说明）。 */
@Data
public class HolidayPublishRequest {
    private String changeSummary;
}
