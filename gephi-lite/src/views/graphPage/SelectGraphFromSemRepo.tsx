import { FC, useState, useEffect, useCallback } from "react";
// import { useTranslation } from "react-i18next";
// import { RemoteFile } from "../../core/graph/import/types";
// import { useImportActions } from "../../core/context/dataContexts";
import { InformationTooltip } from "../../components/InformationTooltip";
import Select from "react-select";
import { RemoteFile } from "../../core/graph/import/types";
import { useImportActions } from "../../core/context/dataContexts";
// import { Metric, MetricScriptParameter } from "../../core/metrics/types";
// import { ItemType } from "../../core/types";


type MetricOption = {
  // id/name of the metric
  value: string;
  // metric for node or edge ?
  label: string;
};

interface Namespace {
  namespace: string
}

interface Model {
  model: string,
  namespace: string
}

interface Version {
  defaultFormat: string,
  model: string,
  namespace: string,
  version: string
}

export const SelectGraphFromSemRepo: FC = () => {
  // const { t } = useTranslation();
  // const [ticking, setTicking] = useState(false);
  // const [optionsNamespace, setOptionsNamespace] = useState(["Prueba1", "Prueba2"]);
  const { importFile } = useImportActions();
  const [optionsNamespace, setOptionsNamespace] = useState<MetricOption[] | null[]>([null]);
  const [optionNamespace, setOptionNamespace] = useState<MetricOption | null>(null);
  const [optionsModel, setOptionsModel] = useState<MetricOption[] | null[]>([null]);
  const [optionModel, setOptionModel] = useState<MetricOption | null>(null);
  const [optionsVersion, setOptionsVersion] = useState<MetricOption[] | null[]>([null]);
  const [optionVersion, setOptionVersion] = useState<MetricOption | null>(null);
  // const [optionNamespace, setOptionNamespace] = useState("enact");


  const getNamespaces = useCallback(async () => {
    const data = await fetch("http://localhost:27621/v1/m/").then(data => data.json());
    const array: MetricOption[] = [];
    data.namespaces.items.map((namespace: Namespace) => {
      array.push({ value: namespace.namespace, label: namespace.namespace });
    });
    setOptionsNamespace(array);
    // console.log(optionsNamespace);
  }, []);

  const getModels = useCallback(async () => {
    const data = await fetch(`http://localhost:27621/v1/m/${optionNamespace?.value}`).then(data => data.json());
    const array: MetricOption[] = [];
    data.models.items.map((model: Model) => {
      array.push({ value: model.model, label: model.model });
    });
    setOptionsModel(array);
  }, [optionNamespace]);

  const getVersions = useCallback(async () => {
    const data = await fetch(`http://localhost:27621/v1/m/${optionNamespace?.value}/${optionModel?.value}`).then(data => data.json());
    const array: MetricOption[] = [];
    data.versions.items.map((version: Version) => {
      array.push({ value: version.version, label: version.version });
    });
    setOptionsVersion(array);
    // console.log(optionsNamespace);
  }, [optionNamespace, optionModel]);

  const representFile = useCallback(async () => {
    const file: RemoteFile = { type: "remote", url: `http://localhost:27621/v1/m/${optionNamespace?.value}/${optionModel?.value}/${optionVersion?.value}/content?format=gexf`, filename: `${optionNamespace?.value}_${optionModel?.value}_${optionVersion?.value}` };
    await importFile(file);
    // console.log(optionsNamespace);
  }, [optionNamespace, optionModel, optionVersion, importFile]);

  useEffect(() => {
    if (optionsNamespace != null) {
      getNamespaces();
    }
  }, [getNamespaces, optionsNamespace])

  useEffect(() => {
    if (optionNamespace != null) {
      getModels();
    }
    // console.log("Option changed to", optionNamespace);
  }, [optionNamespace, getModels])

  useEffect(() => {
    if (optionModel != null) {
      getVersions();
    }
    // console.log("Option changed to", optionNamespace);
  }, [optionNamespace, optionModel, getVersions])

  useEffect(() => {
    if (optionVersion != null) {
      representFile()
    }
    // console.log("Option changed to", optionNamespace);
  }, [optionNamespace, optionModel, optionVersion, representFile])

  return (
    <div className="panel-block">
      <h2 className="fs-4 d-flex align-items-center gap-1">
        {/* <StatisticsIcon className="me-1" /> {t("statistics.title")}{" "} */}
        <InformationTooltip>
          <p className="text-muted small m-0">"Select namespace"</p>
        </InformationTooltip>
      </h2>
      <p className="text-muted small d-none d-md-block">Select namespace</p>
      <Select options={optionsNamespace} onChange={setOptionNamespace}></Select>
      {optionNamespace != null ? <><p className="text-muted small d-none d-md-block">Select model</p>
        <Select options={optionsModel} onChange={setOptionModel}></Select></> : <></>}
      {optionModel != null ? <><p className="text-muted small d-none d-md-block">Select version</p>
        <Select options={optionsVersion} onChange={setOptionVersion}></Select></> : <></>}

    </div>
  )
};
