import { FC } from "react";
import { useTranslation } from "react-i18next";

export const SendGraphToGnn: FC = () => {
  const { t } = useTranslation();
  return (
    <>
      <h1>{t("Send To GNN")}</h1>
    </>
  );
};
