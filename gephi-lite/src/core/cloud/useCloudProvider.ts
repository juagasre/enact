import { isNil } from "lodash";
import { useCallback, useState } from "react";

import { useExportActions, useImportActions } from "../context/dataContexts";
import { graphDatasetAtom } from "../graph";
import { useConnectedUser } from "../user/index";
import { useAtom } from "../utils/atoms";
import { CloudFile } from "./types";

// TODO: need to be refaco by atom/action/producer pattern
export function useCloudProvider() {
  const [user] = useConnectedUser();
  const [graphDataset, setGraphDataset] = useAtom(graphDatasetAtom);
  const { exportAsGexf } = useExportActions();
  const { importFile } = useImportActions();

  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);

  const getFiles = useCallback(
    async (skip: number, limit: number) => {
      setLoading(true);
      setError(null);
      let result: Array<CloudFile> = [];
      try {
        if (isNil(user)) throw new Error("You must be logged !");
        result = await user.provider.getFiles(skip, limit);
      } catch (e) {
        setError(e as Error);
        throw e;
      } finally {
        setLoading(false);
      }
      return result;
    },
    [user],
  );

  const openFile = useCallback(
    async (file: CloudFile) => {
      setLoading(true);
      setError(null);
      try {
        await importFile(file);
      } catch (e) {
        setError(e as Error);
        throw e;
      } finally {
        setLoading(false);
      }
    },
    [importFile],
  );

  /**
   * Save the current graph in the provider.
   */
  const saveFile = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      if (isNil(user)) throw new Error("You must be logged !");
      if (!graphDataset.origin || graphDataset.origin.type !== "cloud") throw new Error("Not a cloud graph");
      await exportAsGexf(async (content) => {
        await user.provider.saveFile(graphDataset.origin as CloudFile, content);
      });
    } catch (e) {
      setError(e as Error);
      throw e;
    } finally {
      setLoading(false);
    }
  }, [user, exportAsGexf, graphDataset.origin]);

  /**
   * Save the current graph in the provider.
   */
  const createFile = useCallback(
    async (file: Pick<CloudFile, "filename" | "description" | "isPublic">) => {
      setLoading(true);
      setError(null);
      try {
        if (isNil(user)) throw new Error("You must be logged !");
        await exportAsGexf(async (content) => {
          const result = await user.provider.createFile(file, content);
          setGraphDataset({ ...graphDataset, origin: result });
        });
      } catch (e) {
        setError(e as Error);
        throw e;
      } finally {
        setLoading(false);
      }
    },
    [user, setGraphDataset, graphDataset, exportAsGexf],
  );

  return { loading, error, getFiles, openFile, saveFile, createFile };
}
