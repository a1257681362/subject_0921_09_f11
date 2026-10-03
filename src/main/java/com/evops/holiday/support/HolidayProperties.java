package com.evops.holiday.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 假期日历配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "holiday")
public class HolidayProperties {

    /**
     * 顺延寻找下一工作日的最大扫描天数：超过仍找不到工作日（极端的超长连休/配置错误）
     * 直接报错让人看日历，不静默无限往后推。
     */
    private int maxScanDays = 60;
}
