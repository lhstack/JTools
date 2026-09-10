package com.lhstack.tools.db.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.lhstack.tools.db.entity.MessageAppendItemEntity
import com.lhstack.tools.db.entity.MessageAttachmentEntity
import com.lhstack.tools.db.entity.MessageEventEntity
import com.lhstack.tools.db.entity.ContextCompactionEntity
import com.lhstack.tools.db.entity.MessageProcessingTaskEntity
import com.lhstack.tools.db.entity.CodingEnvironmentEntity

interface MessageEventMapper : BaseMapper<MessageEventEntity>
interface MessageProcessingTaskMapper : BaseMapper<MessageProcessingTaskEntity>
interface MessageAppendItemMapper : BaseMapper<MessageAppendItemEntity>
interface MessageAttachmentMapper : BaseMapper<MessageAttachmentEntity>

interface ContextCompactionMapper : BaseMapper<ContextCompactionEntity>

interface CodingEnvironmentMapper : BaseMapper<CodingEnvironmentEntity>
