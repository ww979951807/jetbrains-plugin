// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.plugins.markdown.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.io.URLUtil
import org.intellij.plugins.markdown.lang.psi.MarkdownPsiElementFactory

class MarkdownTestLink(node: ASTNode) : MarkdownCompositePsiElementBase(node), PsiExternalReferenceHost {
  override fun getPresentableTagName(): String = "test_link"

  override fun getReferences(): Array<PsiReference> = ReferenceProvidersRegistry.getReferencesFromProviders(this)

  internal class Manipulator : AbstractElementManipulator<MarkdownTestLink>() {
    override fun handleContentChange(element: MarkdownTestLink, range: TextRange, newContent: String): MarkdownTestLink {
      val path = range.replace(element.text, URLUtil.encodePath(newContent))
      val file = MarkdownPsiElementFactory.createFile(element.project, "[@test] $path")
      val replacement = PsiTreeUtil.findChildOfType(file, MarkdownTestLink::class.java)
      // Reject edits that remove the path or make Markdown parse only part of it.
      if (replacement == null || replacement.text != path) throw IncorrectOperationException()
      return element.replace(replacement) as MarkdownTestLink
    }
  }
}
