export interface GitWorkspaceSlotProps {
  spaceId: string;
  projectId: string;
  canUploadOrManage: boolean;
  focusTarget?: { kind: "COMMIT" | "WIP"; id: string; highlightNonce: number };
  onQuoteToChat: (ref: {
    kind: "COMMIT_REF" | "WIP_REF";
    projectId: string;
    targetId: string;
    title: string;
  }) => void;
}
