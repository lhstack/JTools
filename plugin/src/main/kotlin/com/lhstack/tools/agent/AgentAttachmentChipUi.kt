package com.lhstack.tools.agent

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.*

object AgentAttachmentChipUi {
    private val draftChipSize: Dimension
        get() = Dimension(JBUI.scale(168), JBUI.scale(28))
    private val draftTitleSize: Dimension
        get() = Dimension(JBUI.scale(116), JBUI.scale(16))
    private val historyChipSize: Dimension
        get() = Dimension(JBUI.scale(172), JBUI.scale(26))
    private val historyTitleSize: Dimension
        get() = Dimension(JBUI.scale(132), JBUI.scale(16))
    private val stripPreferredHeight: Int
        get() = JBUI.scale(40)
    private val stripMinimumHeight: Int
        get() = JBUI.scale(32)

    fun createHorizontalStrip(content: JPanel): JBScrollPane {
        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBar.unitIncrement = 16
            preferredSize = Dimension(0, stripPreferredHeight)
            minimumSize = Dimension(0, stripMinimumHeight)
            maximumSize = Dimension(Int.MAX_VALUE, stripPreferredHeight)
        }
    }

    fun createDraftChip(
        attachment: AgentAttachmentState,
        onOpen: () -> Unit,
        onRemove: () -> Unit,
    ): JPanel {
        val chip = createChipPanel(
            background = JBColor(0xEEF3FF, 0x3A3D40),
            borderColor = JBColor(0xC9D8FF, 0x565B60),
            tooltip = AgentAttachmentPresentationSupport.chipTooltip(attachment),
            preferredSize = draftChipSize,
        )
        val titleLabel = createTitleLabel(
            attachment = attachment,
            titleSize = draftTitleSize,
            maxLength = 18,
            onOpen = onOpen,
        )
        val removeButton = JButton(AllIcons.Actions.Close).apply {
            isFocusable = false
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            margin = JBUI.insets(0)
            preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14))
            minimumSize = preferredSize
            maximumSize = preferredSize
            toolTipText = "移除附件"
            addActionListener { onRemove() }
        }
        chip.add(titleLabel, BorderLayout.CENTER)
        chip.add(removeButton, BorderLayout.EAST)
        installOpenHandler(chip, onOpen)
        return chip
    }

    fun createHistoryChip(
        attachment: AgentAttachmentState,
        onOpen: () -> Unit,
    ): JPanel {
        val chip = createChipPanel(
            background = JBColor(0xF3F6FB, 0x34383C),
            borderColor = JBColor(0xD6E0F5, 0x555B61),
            tooltip = AgentAttachmentPresentationSupport.chipTooltip(attachment),
            preferredSize = historyChipSize,
        )
        chip.add(
            createTitleLabel(
                attachment = attachment,
                titleSize = historyTitleSize,
                maxLength = 22,
                onOpen = onOpen,
            ),
            BorderLayout.CENTER
        )
        installOpenHandler(chip, onOpen)
        return chip
    }

    private fun createChipPanel(
        background: JBColor,
        borderColor: JBColor,
        tooltip: String,
        preferredSize: Dimension,
    ): JPanel {
        return JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = true
            this.background = background
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(borderColor, 1),
                JBUI.Borders.empty(2, 6)
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = tooltip
            this.preferredSize = preferredSize
            minimumSize = preferredSize
            maximumSize = preferredSize
        }
    }

    private fun createTitleLabel(
        attachment: AgentAttachmentState,
        titleSize: Dimension,
        maxLength: Int,
        onOpen: () -> Unit,
    ): JLabel {
        val title = AgentAttachmentPresentationSupport.chipTitle(attachment, maxLength)
        return JLabel(title).apply {
            preferredSize = titleSize
            minimumSize = titleSize
            maximumSize = titleSize
            toolTipText = AgentAttachmentPresentationSupport.chipTooltip(attachment)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            installOpenHandler(this, onOpen)
        }
    }

    private fun installOpenHandler(component: JComponent, onOpen: () -> Unit) {
        component.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                onOpen()
            }
        })
    }
}
