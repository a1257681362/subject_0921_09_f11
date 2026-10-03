package com.evops.holiday.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.holiday.entity.HolidayShiftBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HolidayShiftBatchMapper extends BaseMapper<HolidayShiftBatch> {
    @Select("SELECT NEXT VALUE FOR seq_holiday_shift_batch_no")
    long nextBatchSeq();
}
