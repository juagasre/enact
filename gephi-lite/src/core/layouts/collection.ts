import Graph from "graphology";
import ForceSupervisor, { ForceLayoutSupervisorParameters } from "graphology-layout-force/worker";
import { inferSettings } from "graphology-layout-forceatlas2";
import { ForceAtlas2LayoutParameters } from "graphology-layout-forceatlas2";
import FA2Layout from "graphology-layout-forceatlas2/worker";
import NoverlapLayout, { NoverlapLayoutSupervisorParameters } from "graphology-layout-noverlap/worker";
import circlepack from "graphology-layout/circlepack";
import circular, { CircularLayoutOptions } from "graphology-layout/circular";
import random, { RandomLayoutOptions } from "graphology-layout/random";
import { isNil, isObject } from "lodash";

import { graphDatasetAtom } from "../graph";
import { DataGraph, ItemData } from "../graph/types";
import { dataGraphToFullGraph } from "../graph/utils";
import { Layout, LayoutMapping, SyncLayout, WorkerLayout } from "./types";

// definition of a custom layout function
// eslint-disable-next-line no-new-func
const nodeCoordinatesCustomFn = new Function(`return (
function nodeCoordinates(id, attributes, index, graph) {
  // / Your code goes here
  return { x: Math.random() * 1000, y: Math.random() * 1000 };
} )`)();

/**
 * List of available layouts
 */
export const LAYOUTS: Array<Layout> = [
  {
    id: "random",
    type: "sync",
    description: true,
    parameters: [
      {
        id: "center",
        type: "number",
        description: true,
        defaultValue: 0.5,
      },
      {
        id: "scale",
        type: "number",
        description: true,
        defaultValue: 1000,
      },
    ],
    run: (graph, options) => random(graph, options?.settings) as unknown as LayoutMapping,
  } as SyncLayout<RandomLayoutOptions>,
  {
    id: "circular",
    type: "sync",
    description: true,
    parameters: [
      {
        id: "center",
        type: "number",
        description: true,
        defaultValue: 0.5,
      },
      {
        id: "scale",
        type: "number",
        description: true,
        defaultValue: 1000,
      },
    ],
    run: (graph, options) => circular(graph, options?.settings) as unknown as LayoutMapping,
  } as SyncLayout<CircularLayoutOptions>,
  {
    id: "circlePack",
    type: "sync",
    description: true,
    parameters: [
      {
        id: "groupingField",
        type: "attribute",
        itemType: "nodes",
        required: false,
      },
      {
        id: "center",
        type: "number",
        description: true,
        defaultValue: 0.5,
      },
      {
        id: "scale",
        type: "number",
        description: true,
        defaultValue: 1,
      },
    ],
    run(graph: Graph, options) {
      const { groupingField, center, scale } = options?.settings || {};

      return circlepack(graph, {
        center,
        scale,
        hierarchyAttributes: groupingField ? [groupingField] : [],
      });
    },
  } as SyncLayout<{ scale?: number; groupingField?: string; center?: number }>,
  {
    id: "fa2",
    type: "worker",
    supervisor: FA2Layout,
    buttons: [
      {
        id: "autoSettings",
        description: true,
        getSettings(currentSettings, dataGraph: DataGraph) {
          const infer = inferSettings(dataGraph);
          return infer;
        },
      },
    ],
    parameters: [
      {
        id: "adjustSizes",
        type: "boolean",
        description: true,
        defaultValue: false,
      },
      {
        id: "barnesHutOptimize",
        type: "boolean",
        description: true,
        defaultValue: false,
      },
      { id: "barnesHutTheta", type: "number", description: true, defaultValue: 0.5, min: 0, step: 0.1 },
      {
        id: "edgeWeightInfluence",
        type: "number",
        description: true,
        defaultValue: 1,
      },
      { id: "gravity", type: "number", description: true, defaultValue: 1.0, min: 0, step: 0.01, required: true },
      { id: "linLogMode", type: "boolean", description: true, defaultValue: false },
      { id: "outboundAttractionDistribution", type: "boolean", defaultValue: false },
      { id: "scalingRatio", type: "number", defaultValue: 1, required: true },
      { id: "slowDown", type: "number", defaultValue: 1 },
      { id: "strongGravityMode", type: "boolean", defaultValue: false },
    ],
  } as WorkerLayout<ForceAtlas2LayoutParameters>,
  {
    id: "force",
    type: "worker",
    supervisor: ForceSupervisor,
    parameters: [
      { id: "attraction", type: "number", description: true, defaultValue: 0.0005 },
      { id: "repulsion", type: "number", description: true, defaultValue: 0.1 },
      { id: "gravity", type: "number", description: true, defaultValue: 0.0001 },
      { id: "inertia", type: "number", description: true, defaultValue: 0.6, min: 0, max: 1 },
      { id: "maxMove", type: "number", description: true, defaultValue: 200 },
    ],
  } as WorkerLayout<ForceLayoutSupervisorParameters>,
  {
    id: "noverlap",
    type: "worker",
    description: true,
    supervisor: NoverlapLayout,
    parameters: [
      { id: "gridSize", type: "number", description: true, defaultValue: 20 },
      { id: "margin", type: "number", description: true, defaultValue: 5 },
      { id: "expansion", type: "number", description: true, defaultValue: 1.1 },
      { id: "ratio", type: "number", description: true, defaultValue: 1 },
      { id: "speed", type: "number", description: true, defaultValue: 3 },
    ],
  } as WorkerLayout<NoverlapLayoutSupervisorParameters>,
  {
    id: "script",
    type: "sync",
    description: true,
    parameters: [
      {
        id: "script",
        type: "script",
        functionJsDoc: `/**
* Function that return coordinates for the specified node.
*
* @param {string} id The ID of the node
* @param {Object.<string, number | string | boolean | undefined | null>} attributes Attributes of the node
* @param {number} index The index position of the node in the graph
* @param {Graph} graph The graphology instance (documentation: https://graphology.github.io/ )
* @returns {x: number, y: number} The computed coordinates of the node
*/`,
        defaultValue: nodeCoordinatesCustomFn,
        functionCheck: (fn) => {
          if (!fn) throw new Error("Function is not defined");
          // Check & test the function
          const fullGraph = dataGraphToFullGraph(graphDatasetAtom.get());
          const id = fullGraph.nodes()[0];
          const attributs = fullGraph.getNodeAttributes(id);
          const result = fn(id, attributs, 0, fullGraph);
          if (!isObject(result)) throw new Error("Function must returned an object");
          if (isNil(result.x)) throw new Error("Function must returned an object with a `x` property");
          if (isNil(result.y)) throw new Error("Function must returned an object with a `y` property");
        },
      },
    ],
    run(graph: Graph, options) {
      const { script } = options?.settings || {};
      if (!script) {
        console.error("[layout] Custom function is not defined");
        return {};
      }
      // we copy the graph to avoid user to modify it
      const graphCopy = graph.copy();
      Object.freeze(graphCopy);

      const res: LayoutMapping = {};
      graph.nodes().forEach((id, index) => {
        res[id] = script(id, graph.getNodeAttributes(id), index, graphCopy);
      });
      return res;
    },
  } as SyncLayout<{
    script?: (id: string, attributes: ItemData, index: number, graph: Graph) => { x: number; y: number };
  }>,
];
