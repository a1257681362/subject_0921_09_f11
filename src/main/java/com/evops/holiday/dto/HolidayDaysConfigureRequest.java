package com.evops.holiday.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.List;

/** 草稿版本整表配置条目（REST/WORK 分开、同日互斥，服务层校验年份归属）。 */
@Data
public class HolidayDaysConfigureRequest {
    @NotNull(message = "条目列表不能为 null（无条目请传空数组）")
    @Valid
    private List<HolidayDaySpec> days;
}
