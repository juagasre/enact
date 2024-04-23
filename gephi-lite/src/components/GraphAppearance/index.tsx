import { FC } from "react";
import { useTranslation } from "react-i18next";

import { useAppearance, useAppearanceActions } from "../../core/context/dataContexts";
import { ItemType } from "../../core/types";
import ColorPicker from "../ColorPicker";
import { ColorItem } from "./color/ColorItem";
import { LabelSizeItem } from "./label/LabelSizeItem";
import { StringAttrItem } from "./label/StringAttrItem";
import { SizeItem } from "./size/SizeItem";

export const GraphItemAppearance: FC<{ itemType: ItemType }> = ({ itemType }) => {
  const { t } = useTranslation();
  const { showEdges } = useAppearance();
  const { setShowEdges } = useAppearanceActions();

  return (
    <>
      {itemType === "edges" && (
        <div className="panel-block">
          <div className="form-check">
            <input
              className="form-check-input"
              type="checkbox"
              checked={showEdges.value}
              onChange={(e) => setShowEdges({ itemType: "edges", value: e.target.checked })}
              id="show-edges"
            />
            <label className="form-check-label" htmlFor="show-edges">
              {t("appearance.show_edges")}
            </label>
          </div>
        </div>
      )}

      <ColorItem itemType={itemType} />
      <hr className="m-0" />
      <SizeItem itemType={itemType} />
      <hr className="m-0" />
      <StringAttrItem itemType={itemType} itemKey="labels" />
      <LabelSizeItem itemType={itemType} />
      {itemType === "nodes" && (
        <>
          <hr className="m-0" />
          <StringAttrItem itemType={itemType} itemKey="images" />
        </>
      )}
    </>
  );
};

export const GraphGraphAppearance: FC<unknown> = () => {
  const { t } = useTranslation();
  const { backgroundColor } = useAppearance();
  const { setBackgroundColorAppearance } = useAppearanceActions();

  return (
    <div className="panel-block">
      <h3 className="fs-5">{t("appearance.graph.background.title")}</h3>

      <div className="d-flex align-items-center">
        <label className="me-3">{t("appearance.graph.background.color")}</label>
        <ColorPicker
          className="w-100 h-100"
          color={backgroundColor}
          clearable
          onChange={(v) => setBackgroundColorAppearance(v)}
        />
      </div>
    </div>
  );
};
