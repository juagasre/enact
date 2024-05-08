package eu.assistiot.semantic_repo.core.documentation

import eu.assistiot.semantic_repo.core.documentation.local.*

/**
 * Global registry of installed documentation compilation plugins.
 */
object DocPluginRegistry:
  sealed trait CheckPluginResponse
  case class PluginOk() extends CheckPluginResponse
  case class PluginError(message: String) extends CheckPluginResponse

  /**
   * List of plugins that are enabled in this Semantic Repository instance.
   */
  final val enabledPlugins: Set[DocPlugin] = Set(
    MarkdownPlugin, MarkdownGitHubPlugin, RstPlugin
  )

  private lazy val pluginMap = Map.from(enabledPlugins.map(p => p.name -> p))

  /**
   * Check if the given plugin is registered.
   * TODO: implement parameter name checking here (#58).
   * @param pluginName plugin name / its key in the API
   * @return CheckPluginResponse
   */
  def checkPlugin(pluginName: String): CheckPluginResponse =
    if pluginMap.contains(pluginName) then
      PluginOk()
    else
      PluginError(s"Documentation plugin '$pluginName' is not registered.")

  /**
   * Returns a filter for filenames that are allowed for a given plugin.
   * @param pluginName plugin name / its key in the API
   * @return filtering method
   */
  def getFilenameFilter(pluginName: String): String => Boolean =
    pluginMap.get(pluginName) match
      case Some(plugin) => plugin.isFilenameAllowed
      case None => (_ => false)
