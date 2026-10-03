package com.evops.holiday.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.holiday.entity.HolidayCalendar;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HolidayCalendarMapper extends BaseMapper<HolidayCalendar> {
    /** 假期日历编号全局序列，并发取号不重。 */
    @Select("SELECT NEXT VALUE FOR seq_holiday_calendar_no")
    long nextCalendarSeq();
}
