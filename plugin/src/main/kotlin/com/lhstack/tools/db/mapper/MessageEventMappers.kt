package com.lhstack.tools.db.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.lhstack.tools.db.entity.MessageAppendItemEntity
import com.lhstack.tools.db.entity.MessageAttachmentEntity
import com.lhstack.tools.db.entity.MessageEventEntity
import com.lhstack.tools.db.entity.ContextCompactionEntity
import com.lhstack.tools.db.entity.MessageProcessingTaskEntity
import com.lhstack.tools.db.entity.AgentEventEntity
import com.lhstack.tools.db.entity.CodingEnvironmentEntity
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

interface MessageEventMapper : BaseMapper<MessageEventEntity> {
    @Select(
        """
        with
        normal_events as (
            select e.id, e.turn_id,
                   cast(coalesce(json_extract(e.context, '$.estimated_tokens'), 0) as integer) as tokens
            from message_events e
            where e.session_id=#{sessionId}
              and e.id > coalesce((select json_extract(config, '$.coding_compacted_through_event_id') from message_sessions where id=#{sessionId}), 0)
              and e.turn_id<>#{currentTurnId}
              and e.parent_event_id is null
              and e.event_type in ('user_message','append_message','model_reply')
              and (
                  e.status in ('completed','failed','cancelled')
                  or (
                      e.event_type='model_reply'
                      and coalesce(
                          json_extract(e.context, '$.response'),
                          json_extract(e.context, '$.text'),
                          json_extract(e.context, '$.message.response'),
                          json_extract(e.context, '$.message.text'),
                          ''
                      ) <> ''
                  )
              )
        ),
        tool_pairs as (
            select c.id as call_id,
                   c.id as result_id,
                   c.turn_id,
                   cast(coalesce(json_extract(c.context, '$.estimated_tokens'), 0) as integer) as tokens,
                   c.id as order_id
            from message_events c
            where c.session_id=#{sessionId}
              and c.id > coalesce((select json_extract(config, '$.coding_compacted_through_event_id') from message_sessions where id=#{sessionId}), 0)
              and c.turn_id<>#{currentTurnId}
              and c.event_type='tool_call'
              and c.parent_event_id is null
              and c.status in ('completed','failed','cancelled')
              and coalesce(json_extract(c.context, '$.extra.type'), '') <> 'subagent'
        ),
        retained_tools as (
            select call_id, result_id, turn_id, tokens, order_id
            from (
                select call_id, result_id, turn_id, tokens, order_id,
                       row_number() over (order by order_id desc) as rank
                from tool_pairs
            )
            where #{toolRetention} is null or rank<=#{toolRetention}
        ),
        layer1 as (
            select n.id as first_id, n.id as last_id, n.turn_id, n.tokens, n.id as order_id
            from normal_events n
            union all
            select r.call_id, r.result_id, r.turn_id, r.tokens, r.order_id
            from retained_tools r
        ),
        layer1_turns as (
            select turn_id, max(order_id) as order_id
            from layer1
            group by turn_id
        ),
        retained_turns as (
            select turn_id
            from (
                select turn_id,
                       row_number() over (order by order_id desc) as rank
                from layer1_turns
            )
            where #{maxRounds} is null or rank<=#{maxRounds}
        ),
        layer2 as (
            select l.first_id, l.last_id, l.tokens
            from layer1 l
            join retained_turns t on t.turn_id=l.turn_id
        ),
        budgeted_units as (
            select first_id, last_id,
                   sum(tokens) over (
                       order by last_id desc
                       rows between unbounded preceding and current row
                   ) as used_tokens
            from layer2
        ),
        selected_ids as (
            select first_id as id from budgeted_units where used_tokens<=#{budget}
            union
            select last_id as id from budgeted_units where used_tokens<=#{budget}
        )
        select e.id,e.parent_event_id,e.session_id,e.turn_id,e.status,e.event_type,
               e.event_id,e.summary,e.context,e.revision,e.created_at,e.updated_at
        from message_events e
        join selected_ids s on s.id=e.id
        order by e.id
        """
    )
    fun selectContextHistoryEvents(
        @Param("sessionId") sessionId: Long,
        @Param("currentTurnId") currentTurnId: String,
        @Param("maxRounds") maxRounds: Long?,
        @Param("toolRetention") toolRetention: Long?,
        @Param("budget") budget: Long,
    ): List<MessageEventEntity>

    @Select(
        """
        select coalesce(sum(cast(coalesce(json_extract(context, '$.estimated_tokens'), 0) as integer)), 0)
        from message_events
        where session_id=#{sessionId}
          and ifnull(parent_event_id, 0) = 0
          and id > coalesce((select json_extract(config, '$.coding_compacted_through_event_id') from message_sessions where id=#{sessionId}), 0)
          and (
                event_type in ('user_message','append_message','model_reply')
                or event_type='tool_call'
              )
          and (
                event_type<>'tool_call'
                or coalesce(json_extract(context, '$.extra.type'), '') <> 'subagent'
              )
        """
    )
    fun selectRemainingHistoryTokens(@Param("sessionId") sessionId: Long): Long
}

interface MessageProcessingTaskMapper : BaseMapper<MessageProcessingTaskEntity>
interface MessageAppendItemMapper : BaseMapper<MessageAppendItemEntity>
interface MessageAttachmentMapper : BaseMapper<MessageAttachmentEntity>

interface ContextCompactionMapper : BaseMapper<ContextCompactionEntity>

interface CodingEnvironmentMapper : BaseMapper<CodingEnvironmentEntity>

interface AgentEventMapper : BaseMapper<AgentEventEntity>
