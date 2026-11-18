import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter } from "react-router-dom";
import { App } from "./app/App";
import { queryClient } from "./shared/api/access";
import "./index.css";

// Remove email challenge tokens from browser history before mounting the router.
const url = new URL(window.location.href);
let challenge: { path: string; token: string } | undefined;
if (["/verify-email", "/reset-password"].includes(url.pathname)) {
  const token = url.searchParams.get("token") ?? new URLSearchParams(url.hash.slice(1)).get("token");
  if (token) {
    challenge = { path: url.pathname, token };
    history.replaceState(history.state, "", url.pathname);
  }
}

createRoot(document.getElementById("root")!).render(
  <StrictMode><QueryClientProvider client={queryClient}><BrowserRouter><App challenge={challenge} /></BrowserRouter></QueryClientProvider></StrictMode>,
);
