package eu.assistiot.semantic_repo.core.documentation.local

import laika.format.{HTML, Markdown}

object MarkdownPlugin extends LocalDocPlugin:
  val name = "markdown"
  val description = "Markdown (vanilla)"

  override def allowedExtensions = super.allowedExtensions ++ Set(
    "md", "markdown"
  )

  override protected def getTransformer =
    LaikaUtil.makeTransformer(Markdown, HTML)
