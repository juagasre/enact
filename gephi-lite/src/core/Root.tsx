import { FC, useMemo } from "react";
import { ErrorBoundary } from "react-error-boundary";
import { HashRouter, Route, Routes } from "react-router-dom";

import { ErrorComponent } from "../components/Error";
import { NotFoundPage } from "../views/NotFoundPage";
import { GraphPage } from "../views/graphPage";
import { Initialize } from "./Initialize";
import { AtomsContextsRoot } from "./context/dataContexts";
import { UIContext, emptyUIContext } from "./context/uiContext";

export const Root: FC = () => {
  const portalTarget = useMemo(() => document.getElementById("portal-target") as HTMLDivElement, []);

  return (
    <ErrorBoundary
      FallbackComponent={ErrorComponent}
      onReset={(details) => {
        // Reset the state of your app so the error doesn't happen again
        console.debug(details);
      }}
    >
      <HashRouter>
        <UIContext.Provider
          value={{
            ...emptyUIContext,
            portalTarget: portalTarget,
          }}
        >
          <AtomsContextsRoot>
            <Initialize>
              <Routes>
                <Route path="/" element={<GraphPage />} />

                {/* Error pages: */}
                <Route path="*" element={<NotFoundPage />} />
              </Routes>
            </Initialize>
          </AtomsContextsRoot>
        </UIContext.Provider>
      </HashRouter>
    </ErrorBoundary>
  );
};
