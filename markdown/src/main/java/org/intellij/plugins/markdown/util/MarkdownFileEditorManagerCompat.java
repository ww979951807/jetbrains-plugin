// Copyright 2000-2024 JetBrains s.r.o. and contributors: use of this source code is governed by the Apache 2.0 license.
package org.intellij.plugins.markdown.util;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;

// FileEditorManager is Kotlin (companion + @JvmStatic) upstream but pure Java in rebased; Kotlin call
// sites compile to Companion.getInstance and NoSuchFieldError at runtime. javac emits INVOKESTATIC on
// the static method, which exists in both platforms.
public final class MarkdownFileEditorManagerCompat {
  private MarkdownFileEditorManagerCompat() {}

  public static FileEditorManager getInstance(Project project) {
    return FileEditorManager.getInstance(project);
  }
}
