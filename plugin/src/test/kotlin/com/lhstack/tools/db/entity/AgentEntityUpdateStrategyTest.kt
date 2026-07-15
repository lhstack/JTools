package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldStrategy
import com.baomidou.mybatisplus.annotation.TableField
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentEntityUpdateStrategyTest {
    @Test
    fun `extra prompt null value participates in update`() {
        val field = AgentEntity::class.java.getDeclaredField("extraPrompt")
        val annotation = field.getAnnotation(TableField::class.java)

        assertEquals(FieldStrategy.IGNORED, annotation.updateStrategy)
    }
}
