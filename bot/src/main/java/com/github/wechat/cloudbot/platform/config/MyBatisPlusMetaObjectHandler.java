package com.github.wechat.cloudbot.platform.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充处理器（默认）。
 *
 * <p>实体上标注了 {@code @TableField(fill = FieldFill.INSERT)} 的字段
 * 在 insert 时自动填充，{@code FieldFill.INSERT_UPDATE} 的字段在 insert/update 时自动填充。
 * 没有此 Bean 时，{@code createdAt}/{@code updatedAt}/{@code isDeleted} 不会被自动赋值，
 * 可能导致 NOT NULL 列写入失败或逻辑删除标记缺失。</p>
 */
@Primary
@Component
public class MyBatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "isDeleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
