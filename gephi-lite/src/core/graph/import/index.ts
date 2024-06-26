import Graph from "graphology";
import gexf from "graphology-gexf";
import graphml from "graphology-graphml/browser";

import { resetStates } from "../../context/dataContexts";
import { preferencesActions } from "../../preferences";
import { resetCamera } from "../../sigma";
import { userAtom } from "../../user";
import { atom } from "../../utils/atoms";
import { asyncAction } from "../../utils/producers";
import { graphDatasetActions } from "../index";
import { initializeGraphDataset } from "../utils";
import { GraphOrigin, ImportState } from "./types";

function getEmptyImportState(): ImportState {
  return { type: "idle" };
}

/**
 * Public API:
 * ***********
 */
export const importStateAtom = atom<ImportState>(getEmptyImportState());

/**
 * Actions:
 * ********
 */
export const importFile = asyncAction(async (file: NonNullable<GraphOrigin>) => {
  if (importStateAtom.get().type === "loading") throw new Error("A file is already being loaded");
  importStateAtom.set({ type: "loading" });
  try {
    // Get file content
    let content: string | null = null;
    let formatParam: string | null = null;
    switch (file.type) {
      case "local":
        content = await file.source.text();
        break;
      case "remote": {
        const response = await fetch(file.url);
        content = await response.text();
        console.log(file.url);
        const pruebaUrl = new URL(file.url)
        const queryParameters = new URLSearchParams(pruebaUrl.search);
        formatParam = queryParameters.get("format");
        // console.log(content);
        break;
      }
      case "cloud": {
        const user = userAtom.get();
        if (!user) throw new Error("Cannot open a cloud file without to be connected");
        content = await user.provider.getFileContent(file.id);
        break;
      }
      default:
        content = null;
        break;
    }
    if (content === null) throw new Error(`Type ${file.type} for file ${file.filename} is not recognized`);

    // Based on file extension, parse it to build a graphology

    const extension = (file.filename.split(".").pop() || "").toLowerCase();
    let graph: Graph | null = null;
    switch (extension) {
      case "gexf":
        graph = gexf.parse(Graph, content, { allowUndeclaredAttributes: true, addMissingNodes: true });
        break;
      case "graphml":
        graph = graphml.parse(Graph, content, { addMissingNodes: true });
        break;
      default:
        graph = null;
        break;
    }
    if (graph === null && formatParam != null) {
      graph = gexf.parse(Graph, content, { allowUndeclaredAttributes: true, addMissingNodes: true });
    }

    if (graph === null) throw new Error(`Extension ${extension} for file ${file.filename} is not recognized`);

    // import it
    const { setGraphDataset } = graphDatasetActions;
    const { addRemoteFile } = preferencesActions;
    graph.setAttribute("title", file.filename);
    resetStates(false);
    setGraphDataset({ ...initializeGraphDataset(graph), origin: file });
    if (file.type === "remote") addRemoteFile(file);
    resetCamera({ forceRefresh: false });
  } catch (e) {
    importStateAtom.set({ type: "error", message: (e as Error).message });
    throw e;
  } finally {
    importStateAtom.set({ type: "idle" });
  }
});

export const importActions = {
  importFile,
};
