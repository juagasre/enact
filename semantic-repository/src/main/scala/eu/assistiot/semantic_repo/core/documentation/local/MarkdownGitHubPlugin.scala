package eu.assistiot.semantic_repo.core.documentation.local

import laika.format.{HTML, Markdown}
import laika.markdown.github.GitHubFlavor

object MarkdownGitHubPlugin extends LocalDocPlugin:
  val name = "gfm"
  val description = "GitHub-flavored Markdown"

  override def allowedExtensions = MarkdownPlugin.allowedExtensions

  override protected def getTransformer =
    LaikaUtil.makeTransformer(Markdown, HTML, Seq(GitHubFlavor))
