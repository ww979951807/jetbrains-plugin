package org.intellij.plugins.markdown.model.psi.labels

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.parentOfTypes
import com.intellij.psi.util.siblings
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownImage
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkLabel
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkText
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownShortReferenceLink
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownTestLink
import org.intellij.plugins.markdown.model.psi.labels.LinkLabelSymbol.Companion.isDeclaration
import org.intellij.plugins.markdown.util.isFootnoteLabelText

internal class LinkLabelSymbolReferenceProvider: PsiSymbolReferenceProvider {
  override fun getReferences(element: PsiExternalReferenceHost, hints: PsiSymbolReferenceHints): Collection<PsiSymbolReference> {
    if (element !is MarkdownLinkLabel || element.isDeclaration) {
      return emptyList()
    }
    if (element.parent?.siblings(forward = true, withSelf = false)?.firstOrNull { it !is PsiWhiteSpace } is MarkdownTestLink) {
      return emptyList()
    }
    if (element.parentOfTypes(MarkdownLinkText::class, MarkdownImage::class) is MarkdownLinkText) {
      return emptyList()
    }
    val elementText = element.text
    val rangeInElement = element.labelTextRange
    val text = rangeInElement.substring(elementText)
    // The test-link parser creates a node only for the path and leaves [@test] as a shortcut reference link.
    // Exclude the marker so it does not shown as unresolved
    if (text == "@test" && element.parent is MarkdownShortReferenceLink &&
        PsiTreeUtil.skipWhitespacesForward(element.parent) is MarkdownTestLink) {
      return emptyList()
    }
    // Footnote reference labels are handled separately and are not link-label symbols.
    if (isFootnoteLabelText(elementText)) {
      return emptyList()
    }
    return listOf(LinkLabelSymbolReference(element, rangeInElement, text))
  }

  override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> {
    return emptyList()
  }
}
