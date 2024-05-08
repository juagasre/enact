package eu.assistiot.semantic_repo.core.documentation.local

import laika.ast.Path.*
import laika.format.{HTML, ReStructuredText}
import laika.helium.Helium
import laika.helium.config.{HeliumIcon, IconLink}

object RstPlugin extends LocalDocPlugin:
  val name = "rst"
  val description = "reStructuredText"

  override def allowedExtensions = super.allowedExtensions ++ Set("rst")

  override protected def getTransformer =
    LaikaUtil.makeTransformer(ReStructuredText, HTML)
