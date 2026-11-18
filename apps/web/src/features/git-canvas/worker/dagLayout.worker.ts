import type { ProjectGraph } from "@orbitspace/shared-types";
import {
  computeDagLayout,
  type DagFilterOptions,
  type DagLayoutResult,
} from "./dagLayoutCore";

export interface DagWorkerRequest {
  requestId: number;
  graph: ProjectGraph;
  previousLanes?: Record<string, number>;
  options?: DagFilterOptions;
}

export interface DagWorkerResponse {
  requestId: number;
  result?: DagLayoutResult;
  error?: string;
}

interface WorkerMessageTarget {
  addEventListener: (
    type: "message",
    listener: (event: MessageEvent<DagWorkerRequest>) => void,
  ) => void;
  postMessage: (message: DagWorkerResponse) => void;
}

const workerScope = globalThis as unknown as WorkerMessageTarget;

workerScope.addEventListener("message", (event: MessageEvent<DagWorkerRequest>) => {
  const { requestId, graph, previousLanes, options } = event.data;
  try {
    const result = computeDagLayout(graph, previousLanes, options);
    workerScope.postMessage({ requestId, result });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : "DAG layout worker failed";
    workerScope.postMessage({ requestId, error: message });
  }
});
