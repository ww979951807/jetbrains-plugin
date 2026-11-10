package com.haifasoft.haifassh.fs.ui

import com.intellij.openapi.ui.GraphicsConfig
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.ToolTipManager

/**
 * An icon button with native IntelliJ hover highlight, hand cursor, and tooltip
 * so the user clearly sees which button they are hovering over before pressing it.
 */
class HoverIconButton(
    val tooltip: String,
    var icon: Icon,
    private val action: () -> Unit,
) : JComponent() {

    private var isHovered = false
    private var isPressed = false

    init {
        toolTipText = tooltip
        ToolTipManager.sharedInstance().registerComponent(this)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = true
        val size = Dimension(JBUI.scale(26), JBUI.scale(26))
        preferredSize = size
        minimumSize = size
        maximumSize = size

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (isEnabled) {
                    isHovered = true
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                isHovered = false
                isPressed = false
                repaint()
            }

            override fun mousePressed(e: MouseEvent) {
                if (isEnabled && e.button == MouseEvent.BUTTON1) {
                    isPressed = true
                    repaint()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (isEnabled && e.button == MouseEvent.BUTTON1) {
                    val wasPressed = isPressed
                    isPressed = false
                    repaint()
                    if (wasPressed && contains(e.point)) {
                        action()
                    }
                }
            }
        })
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        if (!enabled) {
            isHovered = false
            isPressed = false
        }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            val config = GraphicsConfig(g2)
            config.setupRoundedBorderAntialiasing()

            val arc = JBUI.scale(6).toFloat()
            val w = width.toFloat()
            val h = height.toFloat()

            if (isPressed && isEnabled) {
                val pressedBg = runCatching { JBUI.CurrentTheme.ActionButton.pressedBackground() }.getOrNull()
                    ?: JBColor(Color(0, 0, 0, 45), Color(255, 255, 255, 45))
                g2.color = pressedBg
                g2.fill(RoundRectangle2D.Float(1f, 1f, w - 2f, h - 2f, arc, arc))
            } else if (isHovered && isEnabled) {
                val hoverBg = runCatching { JBUI.CurrentTheme.ActionButton.hoverBackground() }.getOrNull()
                    ?: JBColor(Color(0, 0, 0, 25), Color(255, 255, 255, 30))
                g2.color = hoverBg
                g2.fill(RoundRectangle2D.Float(1f, 1f, w - 2f, h - 2f, arc, arc))

                val hoverBorder = runCatching { JBUI.CurrentTheme.ActionButton.hoverBorder() }.getOrNull()
                if (hoverBorder != null) {
                    g2.color = hoverBorder
                    g2.draw(RoundRectangle2D.Float(1f, 1f, w - 2f, h - 2f, arc, arc))
                }
            }

            if (!isEnabled) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)
            }

            val ix = (width - icon.iconWidth) / 2
            val iy = (height - icon.iconHeight) / 2
            icon.paintIcon(this, g2, ix, iy)
            config.restore()
        } finally {
            g2.dispose()
        }
    }
}
