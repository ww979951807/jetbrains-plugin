// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.plugins.markdown.breadcrumbs

import com.intellij.codeInsight.breadcrumbs.FileBreadcrumbsCollector
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.ui.components.breadcrumbs.Crumb
import com.intellij.ui.components.breadcrumbs.StickyLineInfo
import com.intellij.util.text.CharArrayUtil
import com.intellij.xml.breadcrumbs.PsiFileBreadcrumbsCollector
import org.intellij.plugins.markdown.folding.MarkdownFoldingBuilder
import org.intellij.plugins.markdown.lang.hasMarkdownType

internal class MarkdownFileBreadcrumbsCollector(private val project: Project) : FileBreadcrumbsCollector() {
  private val psiCollector = PsiFileBreadcrumbsCollector(project)

  override fun handlesFile(virtualFile: VirtualFile): Boolean = virtualFile.hasMarkdownType() && psiCollector.handlesFile(virtualFile)

  override fun watchForChanges(file: VirtualFile, editor: Editor, disposable: Disposable, changesHandler: Runnable) {
    psiCollector.watchForChanges(file, editor, disposable, changesHandler)
  }

  override fun computeCrumbs(virtualFile: VirtualFile, document: Document, offset: Int, forcedShown: Boolean?): Iterable<Crumb> {
    return psiCollector.computeCrumbs(virtualFile, document, offset, forcedShown)
  }

  override fun computeStickyLineInfos(file: VirtualFile, document: Document, offset: Int): List<StickyLineInfo> {
    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
    return sectionRanges(psiFile).filter { offset in it.startOffset..it.endOffset }.map { StickyLineInfo(it) }
  }

  private fun sectionRanges(file: PsiFile): List<TextRange> {
    return CachedValuesManager.getCachedValue(file) {
      val ranges = ArrayList<TextRange>()
      val text = file.viewProvider.contents
      val visitor = MarkdownFoldingBuilder.HeaderRegionsBuildingVisitor { _, range ->
        val end = CharArrayUtil.shiftBackward(text, range.endOffset - 1, " \t\r\n") + 1
        if (end > range.startOffset) ranges.add(TextRange(range.startOffset, end))
      }
      file.accept(visitor)
      visitor.processLastHeaderIfNeeded()
      CachedValueProvider.Result.create(ranges, file)
    }
  }
}
