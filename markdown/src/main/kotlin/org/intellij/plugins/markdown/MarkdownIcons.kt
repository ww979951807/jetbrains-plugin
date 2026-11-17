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
  val Link: Icon = IconLoader.getIcon("/icons/editor_actions/Link.svg", EditorActionsIconGroup::class.java)
  val NumberedList: Icon = IconLoader.getIcon("/icons/editor_actions/numberedList.svg", EditorActionsIconGroup::class.java)
  val BulletList: Icon = IconLoader.getIcon("/icons/editor_actions/bulletList.svg", EditorActionsIconGroup::class.java)
  val CheckmarkList: Icon = IconLoader.getIcon("/icons/editor_actions/checkmarkList.svg", EditorActionsIconGroup::class.java)
  val Header_level_up: Icon = IconLoader.getIcon("/icons/editor_actions/Header_level_up.svg", EditorActionsIconGroup::class.java)
}
