import React from "react";
import ReactDOM from "react-dom/client";
import { ConfigProvider, theme } from "antd";
import App from "./App";
import "./brutal.css";

/**
 * ConfigProvider re-themes every Ant Design component at once.
 *
 * The tokens below only get antd into the right neighbourhood — flat, square,
 * high-contrast. The actual brutalist treatment (3px ink borders, hard offset
 * shadows, press physics) lives in brutal.css, because antd has no tokens for
 * "shadow with zero blur" or "move 6px on click".
 */
const brutalTheme = {
  algorithm: theme.defaultAlgorithm,
  token: {
    colorPrimary: "#14110f",
    colorInfo: "#7b6cff",
    colorSuccess: "#c9f73d",
    colorError: "#ff4438",
    colorBgBase: "#fffcf2",
    colorTextBase: "#14110f",
    colorBorder: "#14110f",

    // nothing is round
    borderRadius: 0,
    borderRadiusLG: 0,
    borderRadiusSM: 0,

    fontFamily: "'Space Grotesk', ui-sans-serif, system-ui, sans-serif",
    fontSize: 14,
    controlHeight: 40,

    // hard edges only — antd's default soft shadows would fight the theme
    boxShadow: "none",
    boxShadowSecondary: "none",
    lineWidth: 3,
  },
};

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <ConfigProvider theme={brutalTheme}>
      <App />
    </ConfigProvider>
  </React.StrictMode>
);
