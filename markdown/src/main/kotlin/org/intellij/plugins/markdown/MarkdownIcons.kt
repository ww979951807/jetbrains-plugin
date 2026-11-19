// Copyright 2000-2026 JetBrains s.r.o. and contributors: use of this source code is governed by the Apache 2.0 license.
package org.intellij.plugins.markdown

import com.intellij.openapi.util.IconLoader
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon

// Every icon val must be @JvmField: action XML resolves icon="...MarkdownIcons.EditorActions.Link"
// by REFLECTING on public static fields. Kotlin object vals compile to private static + getter,
// which the reflection cannot see -> "Icon cannot be found".
@ApiStatus.Internal
object MarkdownIcons {
  @JvmField
  val EditorActions: EditorActionsIconGroup = EditorActionsIconGroup
  @JvmField
  val ImageGutter: Icon = IconLoader.getIcon("/icons/imageGutter.svg", MarkdownIcons::class.java)
}

@ApiStatus.Internal
object EditorActionsIconGroup {
  @JvmField val Bold: Icon = IconLoader.getIcon("/icons/editor_actions/Bold.svg", EditorActionsIconGroup::class.java)
  @JvmField val Italic: Icon = IconLoader.getIcon("/icons/editor_actions/Italic.svg", EditorActionsIconGroup::class.java)
  @JvmField val Strike_through: Icon = IconLoader.getIcon("/icons/editor_actions/Strike_through.svg", EditorActionsIconGroup::class.java)
  @JvmField val Code_span: Icon = IconLoader.getIcon("/icons/editor_actions/Code_span.svg", EditorActionsIconGroup::class.java)
  @JvmField val Link: Icon = IconLoader.getIcon("/icons/editor_actions/Link.svg", EditorActionsIconGroup::class.java)
  @JvmField val NumberedList: Icon = IconLoader.getIcon("/icons/editor_actions/numberedList.svg", EditorActionsIconGroup::class.java)
  @JvmField val BulletList: Icon = IconLoader.getIcon("/icons/editor_actions/bulletList.svg", EditorActionsIconGroup::class.java)
  @JvmField val CheckmarkList: Icon = IconLoader.getIcon("/icons/editor_actions/checkmarkList.svg", EditorActionsIconGroup::class.java)
  @JvmField val Header_level_up: Icon = IconLoader.getIcon("/icons/editor_actions/Header_level_up.svg", EditorActionsIconGroup::class.java)
  @JvmField val Header_level_down: Icon = IconLoader.getIcon("/icons/editor_actions/Header_level_down.svg", EditorActionsIconGroup::class.java)
  @JvmField val Table: Icon = IconLoader.getIcon("/icons/editor_actions/table.svg", EditorActionsIconGroup::class.java)
  @JvmField val AddRowAbove: Icon = IconLoader.getIcon("/icons/editor_actions/addRowAbove.svg", EditorActionsIconGroup::class.java)
  @JvmField val AddRowBelow: Icon = IconLoader.getIcon("/icons/editor_actions/addRowBelow.svg", EditorActionsIconGroup::class.java)
  @JvmField val AddColumnLeft: Icon = IconLoader.getIcon("/icons/editor_actions/addColumnLeft.svg", EditorActionsIconGroup::class.java)
  @JvmField val AddColumnRight: Icon = IconLoader.getIcon("/icons/editor_actions/addColumnRight.svg", EditorActionsIconGroup::class.java)
  @JvmField val LeftAlign: Icon = IconLoader.getIcon("/icons/editor_actions/leftAlign.svg", EditorActionsIconGroup::class.java)
  @JvmField val CenterAlign: Icon = IconLoader.getIcon("/icons/editor_actions/centerAlign.svg", EditorActionsIconGroup::class.java)
  @JvmField val RightAlign: Icon = IconLoader.getIcon("/icons/editor_actions/rightAlign.svg", EditorActionsIconGroup::class.java)
}
