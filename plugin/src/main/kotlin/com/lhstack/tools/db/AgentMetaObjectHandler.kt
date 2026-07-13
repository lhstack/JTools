package com.lhstack.tools.db

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler
import org.apache.ibatis.reflection.MetaObject
import java.time.LocalDateTime

/** MyBatis-Plus 运行时填充入口，使用具名类避免混淆匿名实现的方法被裁剪。 */
class AgentMetaObjectHandler : MetaObjectHandler {
    override fun insertFill(metaObject: MetaObject) {
        strictInsertFill(metaObject, "createdAt", String::class.java, nowText())
        strictInsertFill(metaObject, "updatedAt", String::class.java, nowText())
    }

    override fun updateFill(metaObject: MetaObject) {
        strictUpdateFill(metaObject, "updatedAt", String::class.java, nowText())
    }

    private fun nowText(): String = LocalDateTime.now().toString().replace('T', ' ')
}
