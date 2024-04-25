import { FC, useState, useEffect, useCallback } from "react";
// import { useTranslation } from "react-i18next";
import { RemoteFile } from "../../core/graph/import/types";
import { useImportActions } from "../../core/context/dataContexts";

export const SendGraphToGnn: FC = () => {
  // const { t } = useTranslation();
  const { importFile } = useImportActions();
  const [ticking, setTicking] = useState(false);
  const [count, setCount] = useState(0);

  const handleActivation = useCallback(async () => {
    const file: RemoteFile = { type: "remote", url: "http://localhost:27621/v1/m/enact/enact/1.0.0/content?format=gexf", filename: "prueba.gexf" };
    await importFile(file);
  }, [importFile]);

  useEffect(() => {
    handleActivation();
    const timer = setTimeout(() => ticking && setCount(count + 1), 2e3);
    return () => clearTimeout(timer);
  }, [count, ticking, handleActivation])



  return <button onClick={() => setTicking(!ticking)}>
    {ticking ? <>Refresh activated</> : <>Refresh deactivated</>}
  </button>
};
