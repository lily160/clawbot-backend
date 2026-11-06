package com.github.wechat.cloudbot.modules.health.profile.infra;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PatientProfileMapper extends BaseMapper<PatientProfileEntity> {

    /**
     * 物理删除某用户某关系下所有「待确认」记录。
     *
     * <p>必须用原生 SQL，因为实体上的 {@code @TableLogic} 会让
     * {@link BaseMapper#delete} 退化为逻辑删除（UPDATE is_deleted=1），
     * 旧行仍留在表中并占据唯一索引 uk_wechat_relation_name_status，
     * 导致后续 insert 触发唯一键冲突、建档写入失败。
     */
    @Delete("DELETE FROM health_patient_profile "
            + "WHERE wechat_user_id = #{wechatUserId} "
            + "AND relation = #{relation} "
            + "AND status = 0")
    int physicalDeletePending(@Param("wechatUserId") String wechatUserId,
                              @Param("relation") String relation);

    /**
     * 物理删除某用户某关系/姓名下的「已生效」记录。
     *
     * <p>确认建档时必须先调用本方法清理旧的已生效行，否则
     * 把待确认行 update 成 status=1 会撞唯一索引
     * uk_wechat_relation_name_status 导致写入失败。
     * name 用 NULL-safe 比较，兼容 name 为 null 的场景。
     */
    @Delete("DELETE FROM health_patient_profile "
            + "WHERE wechat_user_id = #{wechatUserId} "
            + "AND relation = #{relation} "
            + "AND status = 1 "
            + "AND (name = #{name} OR (name IS NULL AND #{name} IS NULL))")
    int physicalDeleteActive(@Param("wechatUserId") String wechatUserId,
                             @Param("relation") String relation,
                             @Param("name") String name);

}
