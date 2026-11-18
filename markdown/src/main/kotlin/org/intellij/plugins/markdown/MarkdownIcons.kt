// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.plugins.markdown

import com.intellij.openapi.util.IconLoader
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon

@ApiStatus.Internal
object MarkdownIcons {
  val EditorActions: EditorActionsIconGroup = EditorActionsIconGroup
  val ImageGutter: Icon = IconLoader.getIcon("/icons/imageGutter.svg", MarkdownIcons::class.java)
}

@ApiStatus.Internal
object EditorActionsIconGroup {
  val Bold: Icon = IconLoader.getIcon("/icons/editor_actions/Bold.svg", EditorActionsIconGroup::class.java)
  val Italic: Icon = IconLoader.getIcon("/icons/editor_actions/Italic.svg", EditorActionsIconGroup::class.java)
  val Strike_through: Icon = IconLoader.getIcon("/icons/editor_actions/Strike_through.svg", EditorActionsIconGroup::class.java)
  val Code_span: Icon = IconLoader.getIcon("/icons/editor_actions/Code_span.svg", EditorActionsIconGroup::class.java)
  val Link: Icon = IconLoader.getIcon("/icons/editor_actions/Link.svg", EditorActionsIconGroup::class.java)
  val NumberedList: Icon = IconLoader.getIcon("/icons/editor_actions/numberedList.svg", EditorActionsIconGroup::class.java)
  val BulletList: Icon = IconLoader.getIcon("/icons/editor_actions/bulletList.svg", EditorActionsIconGroup::class.java)
  val CheckmarkList: Icon = IconLoader.getIcon("/icons/editor_actions/checkmarkList.svg", EditorActionsIconGroup::class.java)
  val Header_level_up: Icon = IconLoader.getIcon("/icons/editor_actions/Header_level_up.svg", EditorActionsIconGroup::class.java)
  val Header_level_down: Icon = IconLoader.getIcon("/icons/editor_actions/Header_level_down.svg", EditorActionsIconGroup::class.java)
  val Table: Icon = IconLoader.getIcon("/icons/editor_actions/table.svg", EditorActionsIconGroup::class.java)
  val AddRowAbove: Icon = IconLoader.getIcon("/icons/editor_actions/addRowAbove.svg", EditorActionsIconGroup::class.java)
  val AddRowBelow: Icon = IconLoader.getIcon("/icons/editor_actions/addRowBelow.svg", EditorActionsIconGroup::class.java)
  val AddColumnLeft: Icon = IconLoader.getIcon("/icons/editor_actions/addColumnLeft.svg", EditorActionsIconGroup::class.java)
  val AddColumnRight: Icon = IconLoader.getIcon("/icons/editor_actions/addColumnRight.svg", EditorActionsIconGroup::class.java)
  val LeftAlign: Icon = IconLoader.getIcon("/icons/editor_actions/leftAlign.svg", EditorActionsIconGroup::class.java)
  val CenterAlign: Icon = IconLoader.getIcon("/icons/editor_actions/centerAlign.svg", EditorActionsIconGroup::class.java)
  val RightAlign: Icon = IconLoader.getIcon("/icons/editor_actions/rightAlign.svg", EditorActionsIconGroup::class.java)
}
