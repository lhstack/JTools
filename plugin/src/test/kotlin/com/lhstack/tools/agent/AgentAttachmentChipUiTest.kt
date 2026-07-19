package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentAttachmentChipUiTest {

    @Test
    fun `draft chip keeps compact fixed size and exposes tooltip`() {
        val attachment = AgentAttachmentState(
            name = "this-is-a-very-long-image-file-name-for-preview.png",
            path = "C:\\Users\\lhstack\\Pictures\\very\\deep\\this-is-a-very-long-image-file-name-for-preview.png",
            kind = AgentAttachmentKind.IMAGE.id,
        )

        val chip = AgentAttachmentChipUi.createDraftChip(
            attachment = attachment,
            onOpen = {},
            onRemove = {}
        )

        assertEquals(Dimension(168, 28), chip.preferredSize)
        assertEquals(chip.preferredSize, chip.minimumSize)
        assertEquals(chip.preferredSize, chip.maximumSize)
        assertEquals(Cursor.HAND_CURSOR, chip.cursor.type)
        assertTrue(chip.toolTipText!!.contains(attachment.path))
        val titleLabel = chip.components.filterIsInstance<JLabel>().first()
        assertEquals(Dimension(116, 16), titleLabel.preferredSize)
        assertTrue(titleLabel.text.endsWith("..."))
        assertTrue(chip.components.any { it is JButton })
    }

    @Test
    fun `history chip keeps compact fixed size`() {
        val attachment = AgentAttachmentState(
            name = "build-log.txt",
            path = "D:\\projects\\java\\idea-tools\\build-log.txt",
            kind = AgentAttachmentKind.FILE.id,
        )

        val chip = AgentAttachmentChipUi.createHistoryChip(
            attachment = attachment,
            onOpen = {}
        )

        assertEquals(Dimension(172, 26), chip.preferredSize)
        assertEquals(chip.preferredSize, chip.minimumSize)
        assertEquals(chip.preferredSize, chip.maximumSize)
        assertEquals(Cursor.HAND_CURSOR, chip.cursor.type)
        val titleLabel = chip.components.filterIsInstance<JLabel>().first()
        assertEquals(Dimension(132, 16), titleLabel.preferredSize)
    }

    @Test
    fun `horizontal attachment strip keeps horizontal scrollbar and fixed height`() {
        val content = JPanel().apply {
            preferredSize = Dimension(960, 28)
        }

        val scrollPane = AgentAttachmentChipUi.createHorizontalStrip(content)

        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, scrollPane.horizontalScrollBarPolicy)
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, scrollPane.verticalScrollBarPolicy)
        assertEquals(40, scrollPane.preferredSize.height)
        assertEquals(32, scrollPane.minimumSize.height)
        assertTrue(scrollPane.horizontalScrollBar.unitIncrement > 0)
    }
}
