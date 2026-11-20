import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type {
  AiSourceRef,
  CodeCommentDto,
  CodeCommentListResponse,
  DocQuoteAnchor,
  DocumentInfo,
  GrantType,
  SpaceDetail,
  SpaceSummary,
} from "@orbitspace/shared-types";
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  BookOpen,
  Check,
  CheckCircle2,
  Copy,
  FilePlus2,
  FileText,
  FolderGit2,
  FolderOpen,
  FolderPlus,
  History,
  Link2,
  LocateFixed,
  LogOut,
  Orbit,
  Plus,
  Send,
  Settings,
  ShieldCheck,
  Terminal,
  Trash2,
  Users,
  X,
} from "lucide-react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import {
  bindSpaceConnection,
  queryClient,
  spaceKey,
  spaceSignal,
  spacesKey,
  type SpaceAccess,
} from "../shared/api/access";
import { logout } from "../shared/api/httpClient";
import { canManageSpace, canUploadOrManage, useAuth } from "../shared/auth/session";
import { Brand, Button, Field, Loading, ResourceStateCard } from "../shared/ui/UiComponents";
import {
  ChatPane,
  type ChatActiveResource,
  type ChatDraftQuote,
} from "../features/chat/ChatPane";
import {
  forwardDocumentCommentToChat,
  forwardPdfCommentToChat,
  type P3ChatMessage,
} from "../features/chat/chatApi";
import type { SummaryOptions, SummarySourceOption } from "../features/documents/AiSummaryDrawer";
import { DocumentPanel } from "../features/documents/DocumentPanel";
import type { DiscussionQuote } from "../features/documents/documentsApi";
import { PdfReaderPanel } from "../features/documents/PdfReaderPanel";
import { SpaceFilesPanel } from "../features/files/SpaceFilesPanel";
import { formatBytes, listSpaceFiles, shortDigest } from "../features/files/filesApi";
import {
  GitWorkspaceView,
  type CommentFocusTarget,
} from "../features/git-canvas/GitWorkspaceView";
import { fetchProjectGraph } from "../features/git-canvas/gitCanvasApi";
import { RemoteGitSettingsPanel } from "../features/remote-git/RemoteGitSettingsPanel";
import { SpaceSettingsPanel } from "../features/space/SpaceSettingsPanel";
import {
  buildCliQuickstart,
  copyToClipboard,
  createGitProject,
  createSpaceDocument,
  deleteSpaceDocument,
  featureErrorText,
  getSpaceAiConfig,
  getSpaceDetail,
  listDocumentVersions,
  listMySpaces,
  listSpaceDocuments,
  locateDocumentComment,
  makeIdempotencyKey,
  SPACE_ROLE_LABELS,
  upsertSpaceSummary,
} from "../features/space/spaceApi";

type CenterMode = "git" | "docs" | "pdf" | "files";
type GitSubMode = "canvas" | "remote-settings";

interface ActivePdfSelection {
  fileId: string;
  fileVersionId: string;
  title: string;
}

interface DocAnchorBannerState {
  documentId: string;
  commentId: string;
  persistedVersionId: string;
  view: "CURRENT" | "QUOTED_VERSION";
  status: DocQuoteAnchor["anchorStatus"] | "LOADING" | "ERROR";
  message: string;
}

function parseCenterMode(rawTab: string | null): CenterMode {
  if (rawTab === "docs" || rawTab === "pdf" || rawTab === "files") return rawTab;
  return "git";
}

function shortId(value: string): string {
  return value.length > 10 ? value.slice(0, 8) : value;
}

export function SpaceWorkbenchPage() {
  const { spaceId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const currentUserId = useAuth((s) => s.session?.userId ?? null);

  const queryProjectId = searchParams.get("projectId")?.trim() || null;
  const querySnapshotId = searchParams.get("snapshotId")?.trim() || null;
  const queryCommitOid = searchParams.get("commitOid")?.trim() || null;
  const queryDocumentId = searchParams.get("documentId")?.trim() || null;
  const queryFileId = searchParams.get("fileId")?.trim() || null;
  const queryFileVersionId = searchParams.get("fileVersionId")?.trim() || null;
  const queryCommentId = searchParams.get("commentId")?.trim() || null;
  const queryView = searchParams.get("view")?.trim() || null;
  const queryTab = searchParams.get("tab")?.trim() || null;

  const spacesQuery = useQuery({
    queryKey: spacesKey,
    queryFn: ({ signal }) => listMySpaces(signal),
  });

  const currentSpace: SpaceSummary | undefined = spacesQuery.data?.find(
    (item) => item.spaceId === spaceId,
  );

  const access: SpaceAccess | null = currentSpace
    ? { spaceId: currentSpace.spaceId, membershipEpoch: currentSpace.membershipEpoch }
    : null;
  const accessIdentity = access ? `${access.spaceId}:${access.membershipEpoch}` : null;

  const [revokedAccessKey, setRevokedAccessKey] = useState<string | null>(null);
  const isRevoked =
    Boolean(accessIdentity && revokedAccessKey === accessIdentity) ||
    Boolean(access && spaceSignal(access).aborted);

  const [centerMode, setCenterMode] = useState<CenterMode>(() => parseCenterMode(queryTab));
  const [gitSubMode, setGitSubMode] = useState<GitSubMode>("canvas");
  const [highlightedFileId, setHighlightedFileId] = useState<string | null>(null);
  const [activeProjectId, setActiveProjectId] = useState<string | null>(queryProjectId);
  const [activeDocumentId, setActiveDocumentId] = useState<string | null>(queryDocumentId);
  const [docEntry, setDocEntry] = useState<
    { commentId: string; view: "CURRENT" | "QUOTED_VERSION" } | undefined
  >(undefined);
  const [docAnchorBanner, setDocAnchorBanner] = useState<DocAnchorBannerState | null>(null);
  const [docFlashActive, setDocFlashActive] = useState(false);
  const [docRemountNonce, setDocRemountNonce] = useState(0);

  const [activePdf, setActivePdf] = useState<ActivePdfSelection | null>(null);
  const [pdfCommentId, setPdfCommentId] = useState<string | undefined>(undefined);
  const [pdfFocusBanner, setPdfFocusBanner] = useState<string | null>(null);
  const [pdfFlashActive, setPdfFlashActive] = useState(false);
  const [pdfRemountNonce, setPdfRemountNonce] = useState(0);

  const [focusTarget, setFocusTarget] = useState<
    | {
        kind: "COMMIT" | "WIP";
        id: string;
        highlightNonce: number;
      }
    | undefined
  >(undefined);
  const [commentFocusTarget, setCommentFocusTarget] = useState<
    CommentFocusTarget | undefined
  >(undefined);
  const [draftQuote, setDraftQuote] = useState<ChatDraftQuote | null>(null);
  const [chatSyncNonce, setChatSyncNonce] = useState(0);
  const [forwardNotice, setForwardNotice] = useState<string | null>(null);
  const [chatMessagesSnapshot, setChatMessagesSnapshot] = useState<P3ChatMessage[]>([]);

  const [showCreateProject, setShowCreateProject] = useState(false);
  const [newProjectName, setNewProjectName] = useState("");
  const [showCreateDocument, setShowCreateDocument] = useState(false);
  const [newDocumentTitle, setNewDocumentTitle] = useState("");
  const [pendingDeleteDoc, setPendingDeleteDoc] = useState<DocumentInfo | null>(null);
  const [showSettingsDrawer, setShowSettingsDrawer] = useState(false);
  const [copiedCli, setCopiedCli] = useState(false);

  const projectIdemKeyRef = useRef<string>(makeIdempotencyKey());
  const docCreateIdemKeyRef = useRef<string>(makeIdempotencyKey());
  const appliedDeepLinkRef = useRef<string | null>(null);
  const lastNonceRef = useRef(0);
  const settingsDialogRef = useRef<HTMLDialogElement>(null);
  const forwardNoticeTimerRef = useRef<number | null>(null);
  const docFlashTimerRef = useRef<number | null>(null);
  const pdfFlashTimerRef = useRef<number | null>(null);
  const docAnchorLookupRef = useRef<AbortController | null>(null);

  const nextNonce = useCallback(() => {
    const next = Math.max(Date.now(), lastNonceRef.current + 1);
    lastNonceRef.current = next;
    return next;
  }, []);

  const showToast = useCallback((message: string) => {
    setForwardNotice(message);
    if (forwardNoticeTimerRef.current !== null) {
      window.clearTimeout(forwardNoticeTimerRef.current);
    }
    forwardNoticeTimerRef.current = window.setTimeout(() => {
      setForwardNotice(null);
      forwardNoticeTimerRef.current = null;
    }, 5000);
  }, []);

  const triggerDocFlash = useCallback(() => {
    setDocFlashActive(false);
    if (docFlashTimerRef.current !== null) {
      window.clearTimeout(docFlashTimerRef.current);
    }
    window.requestAnimationFrame(() => {
      setDocFlashActive(true);
      docFlashTimerRef.current = window.setTimeout(() => {
        setDocFlashActive(false);
        docFlashTimerRef.current = null;
      }, 2200);
    });
  }, []);

  const triggerPdfFlash = useCallback(() => {
    setPdfFlashActive(false);
    if (pdfFlashTimerRef.current !== null) {
      window.clearTimeout(pdfFlashTimerRef.current);
    }
    window.requestAnimationFrame(() => {
      setPdfFlashActive(true);
      pdfFlashTimerRef.current = window.setTimeout(() => {
        setPdfFlashActive(false);
        pdfFlashTimerRef.current = null;
      }, 2400);
    });
  }, []);

  useEffect(() => {
    setCenterMode(parseCenterMode(queryTab));
    setGitSubMode("canvas");
    setHighlightedFileId(null);
    setDraftQuote(null);
    setShowCreateProject(false);
    setNewProjectName("");
    setShowCreateDocument(false);
    setNewDocumentTitle("");
    setPendingDeleteDoc(null);
    setShowSettingsDrawer(false);
    setFocusTarget(undefined);
    setCommentFocusTarget(undefined);
    setDocEntry(undefined);
    setDocAnchorBanner(null);
    setPdfCommentId(undefined);
    setPdfFocusBanner(null);
    setForwardNotice(null);
    setChatMessagesSnapshot([]);
    appliedDeepLinkRef.current = null;
  }, [spaceId, access?.membershipEpoch, queryTab]);

  useEffect(() => {
    return () => {
      if (forwardNoticeTimerRef.current !== null) {
        window.clearTimeout(forwardNoticeTimerRef.current);
      }
      if (docFlashTimerRef.current !== null) {
        window.clearTimeout(docFlashTimerRef.current);
      }
      if (pdfFlashTimerRef.current !== null) {
        window.clearTimeout(pdfFlashTimerRef.current);
      }
      docAnchorLookupRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!access || isRevoked) return;
    return bindSpaceConnection(access, () => {
      setRevokedAccessKey(`${access.spaceId}:${access.membershipEpoch}`);
      setShowSettingsDrawer(false);
      setDraftQuote(null);
      setFocusTarget(undefined);
      setCommentFocusTarget(undefined);
      setDocEntry(undefined);
      setDocAnchorBanner(null);
      setPdfCommentId(undefined);
      setPdfFocusBanner(null);
      setHighlightedFileId(null);
    });
  }, [access?.spaceId, access?.membershipEpoch, isRevoked]);

  const spaceDetailQuery = useQuery({
    queryKey: access
      ? ([...spaceKey(access), "detail"] as const)
      : ["space-detail-none", spaceId],
    enabled: Boolean(access) && !isRevoked,
    queryFn: ({ signal }) => getSpaceDetail(spaceId, access!, signal),
  });

  const documentsQueryKey = useMemo(
    () => (access ? ([...spaceKey(access), "documents"] as const) : (["space-docs-none", spaceId] as const)),
    [access, spaceId],
  );

  const documentsQuery = useQuery({
    queryKey: documentsQueryKey,
    enabled: Boolean(access) && !isRevoked,
    queryFn: ({ signal }) => listSpaceDocuments(spaceId, access!, signal),
  });

  const spaceFilesQuery = useQuery({
    queryKey: access ? ([...spaceKey(access), "files"] as const) : ["space-files-none", spaceId],
    enabled: Boolean(access) && !isRevoked,
    queryFn: ({ signal }) => listSpaceFiles(spaceId, access!, signal),
  });

  const aiConfigQuery = useQuery({
    queryKey: access ? ([...spaceKey(access), "ai-config"] as const) : ["space-ai-none", spaceId],
    enabled: Boolean(access) && !isRevoked,
    queryFn: ({ signal }) => getSpaceAiConfig(spaceId, access!, signal),
  });

  const resolvedSpace = spaceDetailQuery.data?.space ?? currentSpace;
  const projects = spaceDetailQuery.data?.projects ?? [];
  const documents = documentsQuery.data ?? [];
  const spaceFiles = spaceFilesQuery.data?.items ?? [];

  const pdfFiles = useMemo(
    () =>
      spaceFiles.filter(
        (file) =>
          file.lifecycleState === "ACTIVE" &&
          (file.contentType?.toLowerCase() === "application/pdf" ||
            file.fileName.toLowerCase().endsWith(".pdf")),
      ),
    [spaceFiles],
  );

  const activeDocument = useMemo(
    () => documents.find((doc) => doc.documentId === activeDocumentId) ?? null,
    [activeDocumentId, documents],
  );

  const activeDocVersionsQuery = useQuery({
    queryKey:
      access && activeDocument
        ? ([...spaceKey(access), "document", activeDocument.documentId, "versions"] as const)
        : ["doc-versions-none"],
    enabled:
      Boolean(access) &&
      !isRevoked &&
      centerMode === "docs" &&
      Boolean(activeDocument) &&
      Boolean(aiConfigQuery.data?.enabled) &&
      Boolean(aiConfigQuery.data?.allowedKinds.includes("DOCUMENT_VERSION")),
    queryFn: ({ signal }) =>
      listDocumentVersions(spaceId, activeDocument!.documentId, access!, signal),
  });

  const activeProjectSnapshotsQuery = useQuery({
    queryKey:
      access && activeProjectId
        ? ([...spaceKey(access), "project", activeProjectId, "ai-graph"] as const)
        : ["ai-graph-none"],
    enabled:
      Boolean(access) &&
      !isRevoked &&
      centerMode === "docs" &&
      Boolean(activeProjectId) &&
      Boolean(aiConfigQuery.data?.enabled) &&
      Boolean(aiConfigQuery.data?.allowedKinds.includes("GIT_SNAPSHOT")),
    queryFn: ({ signal }) => fetchProjectGraph(spaceId, activeProjectId!, access!, null, 30, signal),
  });

  // Keep spacesKey list synced if SpaceDetail returned fresher metadata without changing epoch
  useEffect(() => {
    const detailSpace = spaceDetailQuery.data?.space;
    if (!detailSpace || !currentSpace) return;
    if (
      detailSpace.name !== currentSpace.name ||
      detailSpace.role !== currentSpace.role ||
      detailSpace.membershipEpoch !== currentSpace.membershipEpoch ||
      detailSpace.recentlyVisitedAt !== currentSpace.recentlyVisitedAt
    ) {
      upsertSpaceSummary(detailSpace);
    }
  }, [currentSpace, spaceDetailQuery.data?.space]);

  useEffect(() => {
    if (!spaceDetailQuery.isSuccess) return;
    const deepLinkKey = [
      spaceId,
      queryTab ?? "",
      queryProjectId ?? "",
      querySnapshotId ?? "",
      queryCommitOid ?? "",
      queryDocumentId ?? "",
      queryFileId ?? "",
      queryFileVersionId ?? "",
      queryCommentId ?? "",
      queryView ?? "",
    ].join(":");

    if (appliedDeepLinkRef.current !== deepLinkKey) {
      appliedDeepLinkRef.current = deepLinkKey;
      if (queryProjectId && projects.some((p) => p.projectId === queryProjectId)) {
        setActiveProjectId(queryProjectId);
      } else {
        setActiveProjectId((prev) =>
          prev && projects.some((p) => p.projectId === prev)
            ? prev
            : projects[0]?.projectId ?? null,
        );
      }

      if (querySnapshotId) {
        setFocusTarget({ kind: "WIP", id: querySnapshotId, highlightNonce: nextNonce() });
      } else if (queryCommitOid) {
        setFocusTarget({ kind: "COMMIT", id: queryCommitOid, highlightNonce: nextNonce() });
      }

      if (queryDocumentId) {
        setActiveDocumentId(queryDocumentId);
        if (queryCommentId && (queryView === "CURRENT" || queryView === "QUOTED_VERSION")) {
          setDocEntry({ commentId: queryCommentId, view: queryView });
        }
      }

      if (queryFileId && queryFileVersionId) {
        const matched = pdfFiles.find((f) => f.fileId === queryFileId);
        setActivePdf({
          fileId: queryFileId,
          fileVersionId: queryFileVersionId,
          title: matched?.fileName ?? `PDF ${shortId(queryFileId)}`,
        });
        if (queryCommentId) setPdfCommentId(queryCommentId);
      }
      return;
    }

    if (
      projects.length > 0 &&
      (!activeProjectId || !projects.some((p) => p.projectId === activeProjectId))
    ) {
      setActiveProjectId(projects[0]?.projectId ?? null);
    } else if (projects.length === 0 && activeProjectId !== null) {
      setActiveProjectId(null);
    }
  }, [
    activeProjectId,
    nextNonce,
    pdfFiles,
    projects,
    queryCommentId,
    queryCommitOid,
    queryDocumentId,
    queryFileId,
    queryFileVersionId,
    queryProjectId,
    querySnapshotId,
    queryTab,
    queryView,
    spaceDetailQuery.isSuccess,
    spaceId,
  ]);

  useEffect(() => {
    if (!documentsQuery.isSuccess) return;
    if (documents.length === 0) {
      setActiveDocumentId(null);
      return;
    }
    if (!activeDocumentId || !documents.some((d) => d.documentId === activeDocumentId)) {
      setActiveDocumentId(documents[0]!.documentId);
    }
  }, [activeDocumentId, documents, documentsQuery.isSuccess]);

  useEffect(() => {
    if (!spaceFilesQuery.isSuccess) return;
    if (pdfFiles.length === 0) {
      if (!pdfCommentId) setActivePdf(null);
      return;
    }
    if (!activePdf) {
      const first = pdfFiles[0]!;
      setActivePdf({
        fileId: first.fileId,
        fileVersionId: first.sha256,
        title: first.fileName,
      });
    } else {
      const matched = pdfFiles.find((f) => f.fileId === activePdf.fileId);
      if (matched && matched.fileName !== activePdf.title) {
        setActivePdf((prev) => (prev ? { ...prev, title: matched.fileName } : prev));
      }
    }
  }, [activePdf, pdfCommentId, pdfFiles, spaceFilesQuery.isSuccess]);

  useEffect(() => {
    const dialog = settingsDialogRef.current;
    if (!dialog) return;
    if (showSettingsDrawer) {
      if (!dialog.open) dialog.showModal();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [showSettingsDrawer]);

  const exitMutation = useMutation({ mutationFn: logout });

  const createProjectMutation = useMutation({
    mutationFn: ({ name, key }: { name: string; key: string }) => {
      if (!access) throw new Error("Space access unavailable");
      return createGitProject(spaceId, name, access, key);
    },
    onSuccess: (created) => {
      projectIdemKeyRef.current = makeIdempotencyKey();
      setNewProjectName("");
      setShowCreateProject(false);
      if (access) {
        const detailKey = [...spaceKey(access), "detail"] as const;
        queryClient.setQueryData<SpaceDetail>(detailKey, (prev) => {
          if (!prev) return prev;
          const exists = prev.projects.some((p) => p.projectId === created.projectId);
          return exists
            ? prev
            : {
                ...prev,
                projects: [
                  ...prev.projects,
                  { projectId: created.projectId, name: created.name },
                ],
              };
        });
        void queryClient.invalidateQueries({ queryKey: detailKey });
      }
      setActiveProjectId(created.projectId);
      setCenterMode("git");
      setGitSubMode("canvas");
    },
  });

  const createDocumentMutation = useMutation({
    mutationFn: ({ title, key }: { title: string; key: string }) => {
      if (!access) throw new Error("Space access unavailable");
      return createSpaceDocument(spaceId, title, access, key);
    },
    onSuccess: (created) => {
      docCreateIdemKeyRef.current = makeIdempotencyKey();
      setNewDocumentTitle("");
      setShowCreateDocument(false);
      queryClient.setQueryData<DocumentInfo[]>(documentsQueryKey, (prev) => {
        if (!prev) return [created];
        return prev.some((d) => d.documentId === created.documentId)
          ? prev
          : [...prev, created];
      });
      void queryClient.invalidateQueries({ queryKey: documentsQueryKey });
      setActiveDocumentId(created.documentId);
      setDocEntry(undefined);
      setDocAnchorBanner(null);
      setCenterMode("docs");
      showToast(`已创建协作文档「${created.title}」。`);
    },
  });

  const deleteDocumentMutation = useMutation({
    mutationFn: ({ documentId, key }: { documentId: string; key: string }) => {
      if (!access) throw new Error("Space access unavailable");
      return deleteSpaceDocument(spaceId, documentId, access, key);
    },
    onSuccess: (_data, variables) => {
      setPendingDeleteDoc(null);
      queryClient.setQueryData<DocumentInfo[]>(documentsQueryKey, (prev) =>
        prev ? prev.filter((d) => d.documentId !== variables.documentId) : prev,
      );
      void queryClient.invalidateQueries({ queryKey: documentsQueryKey });
      if (activeDocumentId === variables.documentId) {
        setDocEntry(undefined);
        setDocAnchorBanner(null);
      }
      setChatSyncNonce((n) => n + 1);
      showToast("协作文档已删除，关联群聊引用卡片预览将同步失效。");
    },
  });

  const handleCreateProject = (event: FormEvent) => {
    event.preventDefault();
    if (createProjectMutation.isPending) return;
    const trimmed = newProjectName.trim();
    if (!trimmed) return;
    createProjectMutation.mutate({ name: trimmed, key: projectIdemKeyRef.current });
  };

  const handleCreateDocument = (event: FormEvent) => {
    event.preventDefault();
    if (createDocumentMutation.isPending) return;
    const trimmed = newDocumentTitle.trim();
    if (!trimmed) return;
    createDocumentMutation.mutate({ title: trimmed, key: docCreateIdemKeyRef.current });
  };

  const handleQuoteToChat = useCallback(
    (ref: {
      kind: "COMMIT_REF" | "WIP_REF";
      projectId: string;
      targetId: string;
      title: string;
    }) => {
      setDraftQuote({
        ...ref,
        nonce: nextNonce(),
      });
    },
    [nextNonce],
  );

  const handleQuoteFileToChat = useCallback(
    (ref: { grantType: GrantType; targetId: string; title: string }) => {
      setDraftQuote({
        kind: "FILE_REF",
        grantType: ref.grantType,
        targetId: ref.targetId,
        title: ref.title,
        nonce: nextNonce(),
      });
    },
    [nextNonce],
  );

  const handleOpenResourceCard = useCallback(
    (target: { kind: "COMMIT" | "WIP"; projectId: string; targetId: string }) => {
      setCenterMode("git");
      setGitSubMode("canvas");
      setCommentFocusTarget(undefined);
      setActiveProjectId(target.projectId);
      setFocusTarget({
        kind: target.kind,
        id: target.targetId,
        highlightNonce: nextNonce(),
      });
    },
    [nextNonce],
  );

  const handleOpenCodeCommentCard = useCallback(
    (target: { projectId: string; commentId: string }) => {
      if (access) {
        const commentsKey = [
          ...spaceKey(access),
          "project",
          target.projectId,
          "code-comments",
        ] as const;
        const cached = queryClient.getQueryData<CodeCommentListResponse>(commentsKey);
        if (cached && !cached.items.some((item) => item.commentId === target.commentId)) {
          queryClient.removeQueries({ queryKey: commentsKey });
        }
      }
      setCenterMode("git");
      setGitSubMode("canvas");
      setFocusTarget(undefined);
      setActiveProjectId(target.projectId);
      setCommentFocusTarget({
        commentId: target.commentId,
        highlightNonce: nextNonce(),
      });
    },
    [access, nextNonce],
  );

  const handleOpenFileCard = useCallback(
    (target: { grantType: GrantType; targetId: string }) => {
      if (target.grantType === "SPACE_FILE") {
        setCenterMode("files");
        setHighlightedFileId(target.targetId);
      } else {
        navigate(`/airdrops?shareId=${encodeURIComponent(target.targetId)}`);
      }
    },
    [navigate],
  );

  const handleOpenDocCard = useCallback(
    (target: {
      documentId: string;
      commentId: string;
      persistedVersionId: string;
      view: "CURRENT" | "QUOTED_VERSION";
    }) => {
      if (
        centerMode === "docs" &&
        activeDocumentId === target.documentId &&
        docEntry?.commentId === target.commentId &&
        docEntry?.view === target.view
      ) {
        setDocRemountNonce((n) => n + 1);
      }
      setCenterMode("docs");
      setActiveDocumentId(target.documentId);
      setDocEntry({ commentId: target.commentId, view: target.view });
      triggerDocFlash();

      if (!access) return;
      docAnchorLookupRef.current?.abort();
      const controller = new AbortController();
      docAnchorLookupRef.current = controller;

      setDocAnchorBanner({
        documentId: target.documentId,
        commentId: target.commentId,
        persistedVersionId: target.persistedVersionId,
        view: target.view,
        status: "LOADING",
        message:
          target.view === "CURRENT"
            ? "正在解析当前协作文档中的 Y.RelativePosition 锚点…"
            : `正在打开引用时固化的不可变历史版本 #${shortId(target.persistedVersionId)}…`,
      });

      void locateDocumentComment(
        spaceId,
        target.documentId,
        target.commentId,
        target.view,
        access,
        controller.signal,
      )
        .then((located) => {
          if (controller.signal.aborted) return;
          const anchorStatus = located.anchor.anchorStatus;
          let message: string;
          if (target.view === "QUOTED_VERSION") {
            message = `正在核对引用时不可变历史版本（#${shortId(located.anchor.persistedVersionId)}），支持随时切换查看当前位置。`;
          } else if (anchorStatus === "ACTIVE") {
            message = "已定位当前协作文档引用位置并高亮选区。";
          } else if (anchorStatus === "CONTENT_MODIFIED") {
            message =
              "原文已修改：当前文档中的引用选区或上下文已变化，可点击右侧按钮核对「引用时内容」。";
          } else {
            message =
              "锚点已失效：引用段落在当前文档中已被删除或改写，请点击右侧按钮查看「引用时内容」。";
          }
          setDocAnchorBanner({
            documentId: target.documentId,
            commentId: target.commentId,
            persistedVersionId: located.anchor.persistedVersionId,
            view: target.view,
            status: anchorStatus,
            message,
          });
        })
        .catch((err) => {
          if (controller.signal.aborted) return;
          setDocAnchorBanner({
            documentId: target.documentId,
            commentId: target.commentId,
            persistedVersionId: target.persistedVersionId,
            view: target.view,
            status: "ERROR",
            message: featureErrorText(err),
          });
        });
    },
    [access, activeDocumentId, centerMode, docEntry?.commentId, docEntry?.view, spaceId, triggerDocFlash],
  );

  const handleOpenPdfCard = useCallback(
    (target: {
      fileId: string;
      fileVersionId: string;
      commentId: string;
      pageNumber: number;
    }) => {
      if (
        centerMode === "pdf" &&
        activePdf?.fileId === target.fileId &&
        activePdf?.fileVersionId === target.fileVersionId &&
        pdfCommentId === target.commentId
      ) {
        setPdfRemountNonce((n) => n + 1);
      }
      const matched = pdfFiles.find((f) => f.fileId === target.fileId);
      setCenterMode("pdf");
      setActivePdf({
        fileId: target.fileId,
        fileVersionId: target.fileVersionId,
        title: matched?.fileName ?? `PDF 资料 (${shortId(target.fileId)})`,
      });
      setPdfCommentId(target.commentId);
      setPdfFocusBanner(
        `已定位至第 ${target.pageNumber} 页（文件版本 ${shortDigest(target.fileVersionId)}），正在高亮规范化选区框。`,
      );
      triggerPdfFlash();
    },
    [activePdf?.fileId, activePdf?.fileVersionId, centerMode, pdfCommentId, pdfFiles, triggerPdfFlash],
  );

  const handleForwardCodeComment = useCallback(
    (comment: CodeCommentDto) => {
      setChatSyncNonce((n) => n + 1);
      showToast(`已将代码批注（${comment.filePath}:${comment.lineNumber}）转发至空间主群聊。`);
    },
    [showToast],
  );

  const handleForwardDocOrPdfQuote = useCallback(
    async (quote: DiscussionQuote) => {
      if (!access) throw new Error("空间访问授权不可用");
      const clientMsgId = makeIdempotencyKey();
      const idempotencyKey = makeIdempotencyKey();
      if (quote.kind === "DOC_REF") {
        await forwardDocumentCommentToChat(
          spaceId,
          quote.anchor.documentId,
          quote.commentId,
          { clientMsgId },
          access,
          idempotencyKey,
        );
        setChatSyncNonce((n) => n + 1);
        showToast("已将协作文档选区引用转发至空间主群聊（复用同一讨论串）。");
      } else {
        await forwardPdfCommentToChat(
          spaceId,
          quote.anchor.fileId,
          quote.anchor.fileVersionId,
          quote.commentId,
          { clientMsgId },
          access,
          idempotencyKey,
        );
        setChatSyncNonce((n) => n + 1);
        showToast(
          `已将 PDF 第 ${quote.anchor.pageNumber} 页选区引用转发至空间主群聊（复用同一讨论串）。`,
        );
      }
    },
    [access, showToast, spaceId],
  );

  const handleTodoDraftCommitted = useCallback(
    (documentId: string, title: string) => {
      setCenterMode("docs");
      setActiveDocumentId(documentId);
      setDocEntry(undefined);
      setDocAnchorBanner(null);
      void queryClient.invalidateQueries({ queryKey: documentsQueryKey });
      showToast(`已将讨论结论整理为待办草稿并写入协作文档「${title}」。`);
    },
    [documentsQueryKey, showToast],
  );

  const activeProject = useMemo(
    () => projects.find((p) => p.projectId === activeProjectId) ?? null,
    [activeProjectId, projects],
  );

  // Assemble AI Summary Drawer source options strictly within the space admin's allowedKinds policy
  const aiSummaryOptions: SummaryOptions | undefined = useMemo(() => {
    const policy = aiConfigQuery.data;
    if (!policy?.enabled) return undefined;

    const allowed = new Set(policy.allowedKinds);
    const options: SummarySourceOption[] = [];

    if (allowed.has("DOCUMENT_VERSION") && activeDocument) {
      for (const ver of (activeDocVersionsQuery.data?.versions ?? []).slice(0, 5)) {
        options.push({
          source: {
            kind: "DOCUMENT_VERSION",
            resourceId: activeDocument.documentId,
            versionId: ver.persistedVersionId,
          },
          title: `文档「${activeDocument.title}」版本批次 ${ver.persistedBatchSeq}`,
        });
      }
    }

    if (allowed.has("PDF_VERSION")) {
      for (const file of pdfFiles.slice(0, 10)) {
        options.push({
          source: {
            kind: "PDF_VERSION",
            resourceId: file.fileId,
            versionId: file.sha256,
          },
          title: `PDF「${file.fileName}」(${shortDigest(file.sha256)})`,
        });
      }
    }

    if (allowed.has("GIT_SNAPSHOT") && activeProjectSnapshotsQuery.data) {
      for (const wip of activeProjectSnapshotsQuery.data.wipNodes.slice(0, 8)) {
        options.push({
          source: {
            kind: "GIT_SNAPSHOT",
            resourceId: wip.snapshotId,
            versionId: wip.snapshotId,
          },
          title: `WIP 快照 #${wip.captureSeq} (${shortId(wip.snapshotId)})`,
        });
      }
    }

    if (allowed.has("CHAT_MESSAGE")) {
      const textMessages = chatMessagesSnapshot
        .filter((m) => m.content.kind === "TEXT")
        .slice(-15)
        .reverse();
      for (const msg of textMessages) {
        if (msg.content.kind !== "TEXT") continue;
        const preview =
          msg.content.text.length > 36
            ? `${msg.content.text.slice(0, 36)}…`
            : msg.content.text;
        options.push({
          source: {
            kind: "CHAT_MESSAGE",
            resourceId: msg.messageId,
            versionId: msg.messageId,
          },
          title: `群聊 #${msg.seq}：${preview}`,
        });
      }
    }

    const sourceHref = (source: AiSourceRef): string => {
      const base = `/spaces/${encodeURIComponent(spaceId)}`;
      if (source.kind === "DOCUMENT_VERSION") {
        return `${base}?tab=docs&documentId=${encodeURIComponent(source.resourceId)}`;
      }
      if (source.kind === "PDF_VERSION") {
        return `${base}?tab=pdf&fileId=${encodeURIComponent(source.resourceId)}&fileVersionId=${encodeURIComponent(source.versionId)}`;
      }
      if (source.kind === "GIT_SNAPSHOT") {
        const projectParam = activeProjectId
          ? `&projectId=${encodeURIComponent(activeProjectId)}`
          : "";
        return `${base}?tab=git${projectParam}&snapshotId=${encodeURIComponent(source.resourceId)}`;
      }
      return base;
    };

    return { sources: options, sourceHref };
  }, [
    activeDocVersionsQuery.data?.versions,
    activeDocument,
    activeProjectId,
    activeProjectSnapshotsQuery.data,
    aiConfigQuery.data,
    chatMessagesSnapshot,
    pdfFiles,
    spaceId,
  ]);

  const activeResource: ChatActiveResource | null = useMemo(() => {
    if (centerMode === "git" && activeProject) {
      return { kind: "PROJECT", id: activeProject.projectId, label: activeProject.name };
    }
    if (centerMode === "docs" && activeDocument) {
      return {
        kind: "DOCUMENT",
        id: activeDocument.documentId,
        label: activeDocument.title,
      };
    }
    if (centerMode === "pdf" && activePdf) {
      return {
        kind: "PDF",
        id: activePdf.fileId,
        fileVersionId: activePdf.fileVersionId,
        label: activePdf.title,
      };
    }
    if (centerMode === "files") {
      return { kind: "SPACE_FILES", id: highlightedFileId, label: "空间文件区" };
    }
    return null;
  }, [activeDocument, activePdf, activeProject, centerMode, highlightedFileId]);

  if (!access) {
    if (spacesQuery.isPending) {
      return (
        <section className="workbench-guard-page">
          <Loading label="正在核对空间成员资格与 Epoch…" />
        </section>
      );
    }
    if (spacesQuery.isError) {
      return (
        <section className="workbench-guard-page">
          <div className="alert" role="alert">
            <AlertCircle size={17} />
            <span>{featureErrorText(spacesQuery.error)}</span>
            <Button
              className="secondary workbench-btn-xs"
              onClick={() => {
                void spacesQuery.refetch();
              }}
            >
              重试
            </Button>
          </div>
          <Link className="text-link" to="/">
            <ArrowLeft size={15} /> 返回我的空间
          </Link>
        </section>
      );
    }
    return (
      <section className="workbench-guard-page">
        <ResourceStateCard state="REVOKED_FOR_ACTOR" />
        <Link className="text-link" to="/">
          <ArrowLeft size={15} /> 返回我的空间
        </Link>
      </section>
    );
  }

  if (isRevoked) {
    return (
      <section className="workbench-guard-page">
        <ResourceStateCard state="REVOKED_FOR_ACTOR" />
        <Link className="text-link" to="/">
          <ArrowLeft size={15} /> 返回我的空间
        </Link>
      </section>
    );
  }

  if (!resolvedSpace) {
    return (
      <section className="workbench-guard-page">
        <Loading label="正在加载空间详情…" />
      </section>
    );
  }

  const canManage = canManageSpace(resolvedSpace.role);
  const canWrite = canUploadOrManage(resolvedSpace.role);
  const cliQuickstart = buildCliQuickstart(activeProject?.projectId ?? "<projectId>");

  return (
    <div className="space-workbench-shell">
      <header className="workbench-topbar">
        <div className="workbench-topbar-left">
          <Link className="workbench-brand-link" to="/" title="返回 OrbitSpace 工作台">
            <Brand />
          </Link>
          <Link className="workbench-back-btn" to="/">
            <ArrowLeft size={14} /> 我的空间
          </Link>
          <div className="workbench-space-heading">
            <h1>{resolvedSpace.name}</h1>
            <span className={`space-role-badge role-${resolvedSpace.role.toLowerCase()}`}>
              {SPACE_ROLE_LABELS[resolvedSpace.role]}
            </span>
            <span className="workbench-epoch-tag">Epoch #{resolvedSpace.membershipEpoch}</span>
          </div>
        </div>

        <div className="workbench-topbar-actions">
          {centerMode === "files" ? (
            <span className="workbench-active-project-pill">
              <FolderOpen size={14} /> 空间文件区
            </span>
          ) : centerMode === "docs" ? (
            <span className="workbench-active-project-pill">
              <FileText size={14} /> {activeDocument ? activeDocument.title : "协作文档"}
            </span>
          ) : centerMode === "pdf" ? (
            <span className="workbench-active-project-pill">
              <BookOpen size={14} /> {activePdf ? activePdf.title : "PDF 资料精读"}
            </span>
          ) : activeProject ? (
            <span className="workbench-active-project-pill">
              <FolderGit2 size={14} /> {activeProject.name}
              {gitSubMode === "remote-settings" ? " · 远端来源" : ""}
            </span>
          ) : null}
          <Link className="workbench-nav-link" to="/airdrops">
            <Send size={14} /> 文件空投
          </Link>
          <Link className="workbench-nav-link" to="/friends">
            <Users size={14} /> 好友
          </Link>
          <Link className="workbench-nav-link" to="/device">
            <Terminal size={14} /> 授权 CLI 设备
          </Link>
          <Link className="workbench-nav-link" to="/account">
            <ShieldCheck size={14} /> 账号与安全
          </Link>
          <Button
            className="secondary workbench-btn-xs"
            onClick={() => setShowSettingsDrawer(true)}
          >
            <Settings size={14} /> 空间设置与 AI 策略
          </Button>
          <Button
            className="secondary workbench-btn-xs"
            disabled={exitMutation.isPending}
            onClick={() => exitMutation.mutate()}
          >
            <LogOut size={14} /> 退出登录
          </Button>
        </div>
      </header>

      {forwardNotice && (
        <div className="workbench-forward-toast" role="status">
          <span>
            <CheckCircle2 size={15} /> {forwardNotice}
          </span>
          <Button
            className="icon-button secondary"
            aria-label="关闭提示"
            onClick={() => setForwardNotice(null)}
          >
            <X size={13} />
          </Button>
        </div>
      )}

      {spaceDetailQuery.isError && (
        <div className="alert workbench-top-alert" role="alert">
          <AlertCircle size={16} />
          <span>{featureErrorText(spaceDetailQuery.error)}</span>
          <Button
            className="secondary workbench-btn-xs"
            onClick={() => {
              void spaceDetailQuery.refetch();
            }}
          >
            重试
          </Button>
        </div>
      )}

      <div className="workbench-3pane">
        <aside className="workbench-left-rail" aria-label="空间资源导航">
          <div className="rail-mode-switcher rail-mode-grid-4" role="tablist" aria-label="工作区视图切换">
            <button
              type="button"
              role="tab"
              aria-selected={centerMode === "git"}
              className={`rail-mode-btn ${centerMode === "git" ? "active" : ""}`}
              onClick={() => setCenterMode("git")}
            >
              <FolderGit2 size={14} /> Git 工程
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={centerMode === "docs"}
              className={`rail-mode-btn ${centerMode === "docs" ? "active" : ""}`}
              onClick={() => setCenterMode("docs")}
            >
              <FileText size={14} /> 协作文档
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={centerMode === "pdf"}
              className={`rail-mode-btn ${centerMode === "pdf" ? "active" : ""}`}
              onClick={() => setCenterMode("pdf")}
            >
              <BookOpen size={14} /> PDF 精读
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={centerMode === "files"}
              className={`rail-mode-btn ${centerMode === "files" ? "active" : ""}`}
              onClick={() => setCenterMode("files")}
            >
              <FolderOpen size={14} /> 空间文件
            </button>
          </div>

          {centerMode === "git" && (
            <>
              <div className="rail-section-head">
                <h2>Git 工程列表</h2>
                {canManage && (
                  <Button
                    className="secondary workbench-btn-xs"
                    onClick={() => {
                      setShowCreateProject((prev) => !prev);
                      createProjectMutation.reset();
                      projectIdemKeyRef.current = makeIdempotencyKey();
                    }}
                  >
                    <Plus size={13} /> 新建工程
                  </Button>
                )}
              </div>

              {canManage && showCreateProject && (
                <form className="rail-create-project-form" onSubmit={handleCreateProject}>
                  <Field
                    label="工程名称"
                    name="projectName"
                    required
                    maxLength={120}
                    placeholder="例如：web-app"
                    value={newProjectName}
                    disabled={createProjectMutation.isPending}
                    onChange={(e) => {
                      setNewProjectName(e.target.value);
                      projectIdemKeyRef.current = makeIdempotencyKey();
                    }}
                  />
                  {createProjectMutation.isError && (
                    <div className="alert" role="alert">
                      <AlertCircle size={15} />
                      <span>{featureErrorText(createProjectMutation.error)}</span>
                    </div>
                  )}
                  <div className="actions">
                    <Button
                      className="secondary workbench-btn-xs"
                      disabled={createProjectMutation.isPending}
                      onClick={() => setShowCreateProject(false)}
                    >
                      取消
                    </Button>
                    <Button
                      type="submit"
                      className="primary workbench-btn-xs"
                      disabled={createProjectMutation.isPending || !newProjectName.trim()}
                    >
                      {createProjectMutation.isPending ? "创建中…" : "确认创建"}
                    </Button>
                  </div>
                </form>
              )}

              {spaceDetailQuery.isPending && <Loading label="加载工程列表…" />}

              {spaceDetailQuery.isSuccess && projects.length === 0 && (
                <div className="rail-empty-box">
                  <p className="muted">当前空间尚未创建 Git 工程。</p>
                  {canManage ? (
                    <Button
                      className="primary workbench-btn-xs"
                      onClick={() => setShowCreateProject(true)}
                    >
                      <FolderPlus size={14} /> 新建 Git 工程
                    </Button>
                  ) : (
                    <p className="muted">请联系空间 OWNER 或 ADMIN 创建工程。</p>
                  )}
                </div>
              )}

              {projects.length > 0 && (
                <ul className="rail-project-list">
                  {projects.map((project) => {
                    const isActive = project.projectId === activeProjectId;
                    return (
                      <li key={project.projectId}>
                        <button
                          type="button"
                          className={`rail-project-btn ${isActive ? "is-active" : ""}`}
                          aria-current={isActive ? "true" : undefined}
                          onClick={() => {
                            setActiveProjectId(project.projectId);
                            setCenterMode("git");
                          }}
                        >
                          <FolderGit2 size={15} />
                          <div className="rail-project-meta">
                            <strong>{project.name}</strong>
                            <code>{project.projectId}</code>
                          </div>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}

              {activeProject && (
                <div className="rail-project-subnav">
                  <Button
                    className={`${gitSubMode === "canvas" ? "primary" : "secondary"} workbench-btn-xs`}
                    onClick={() => setGitSubMode("canvas")}
                  >
                    <Orbit size={13} /> 轨道画布
                  </Button>
                  <Button
                    className={`${gitSubMode === "remote-settings" ? "primary" : "secondary"} workbench-btn-xs`}
                    onClick={() => setGitSubMode("remote-settings")}
                  >
                    <Link2 size={13} /> 工程远端设置
                  </Button>
                </div>
              )}
            </>
          )}

          {centerMode === "docs" && (
            <>
              <div className="rail-section-head">
                <h2>协作文档 ({documents.length})</h2>
                {canWrite && (
                  <Button
                    className="secondary workbench-btn-xs"
                    onClick={() => {
                      setShowCreateDocument((prev) => !prev);
                      createDocumentMutation.reset();
                      docCreateIdemKeyRef.current = makeIdempotencyKey();
                    }}
                  >
                    <FilePlus2 size={13} /> 新建文档
                  </Button>
                )}
              </div>

              {canWrite && showCreateDocument && (
                <form className="rail-create-project-form" onSubmit={handleCreateDocument}>
                  <Field
                    label="文档标题"
                    name="documentTitle"
                    required
                    maxLength={255}
                    placeholder="例如：迭代方案与待办"
                    value={newDocumentTitle}
                    disabled={createDocumentMutation.isPending}
                    onChange={(e) => {
                      setNewDocumentTitle(e.target.value);
                      docCreateIdemKeyRef.current = makeIdempotencyKey();
                    }}
                  />
                  {createDocumentMutation.isError && (
                    <div className="alert" role="alert">
                      <AlertCircle size={15} />
                      <span>{featureErrorText(createDocumentMutation.error)}</span>
                    </div>
                  )}
                  <div className="actions">
                    <Button
                      className="secondary workbench-btn-xs"
                      disabled={createDocumentMutation.isPending}
                      onClick={() => setShowCreateDocument(false)}
                    >
                      取消
                    </Button>
                    <Button
                      type="submit"
                      className="primary workbench-btn-xs"
                      disabled={createDocumentMutation.isPending || !newDocumentTitle.trim()}
                    >
                      {createDocumentMutation.isPending ? "创建中…" : "确认创建"}
                    </Button>
                  </div>
                </form>
              )}

              {documentsQuery.isPending && <Loading label="加载协作文档…" />}
              {documentsQuery.isError && (
                <div className="alert" role="alert">
                  <AlertCircle size={15} />
                  <span>{featureErrorText(documentsQuery.error)}</span>
                </div>
              )}
              {deleteDocumentMutation.isError && (
                <div className="alert" role="alert">
                  <AlertCircle size={15} />
                  <span>{featureErrorText(deleteDocumentMutation.error)}</span>
                </div>
              )}

              {pendingDeleteDoc && (
                <div className="settings-confirm-box danger-box" role="alertdialog">
                  <p>
                    <strong>确认删除文档「{pendingDeleteDoc.title}」？</strong>
                    删除后协同连接将立即断开，群聊引用预览同步失效。
                  </p>
                  <div className="actions">
                    <Button
                      className="secondary workbench-btn-xs"
                      disabled={deleteDocumentMutation.isPending}
                      onClick={() => setPendingDeleteDoc(null)}
                    >
                      取消
                    </Button>
                    <Button
                      className="danger workbench-btn-xs"
                      disabled={deleteDocumentMutation.isPending}
                      onClick={() =>
                        deleteDocumentMutation.mutate({
                          documentId: pendingDeleteDoc.documentId,
                          key: makeIdempotencyKey(),
                        })
                      }
                    >
                      {deleteDocumentMutation.isPending ? "删除中…" : "确认删除"}
                    </Button>
                  </div>
                </div>
              )}

              {documentsQuery.isSuccess && documents.length === 0 && (
                <div className="rail-empty-box">
                  <p className="muted">当前空间尚无协作文档。</p>
                  {canWrite ? (
                    <Button
                      className="primary workbench-btn-xs"
                      onClick={() => setShowCreateDocument(true)}
                    >
                      <FilePlus2 size={14} /> 新建协作文档
                    </Button>
                  ) : (
                    <p className="muted">当前角色为只读（VIEWER），仅可查看已有协作文档。</p>
                  )}
                </div>
              )}

              {documents.length > 0 && (
                <ul className="rail-project-list">
                  {documents.map((doc) => {
                    const isActive = doc.documentId === activeDocumentId;
                    return (
                      <li key={doc.documentId} className="rail-doc-item">
                        <button
                          type="button"
                          className={`rail-project-btn ${isActive ? "is-active" : ""}`}
                          aria-current={isActive ? "true" : undefined}
                          onClick={() => {
                            setActiveDocumentId(doc.documentId);
                            setDocEntry(undefined);
                            setDocAnchorBanner(null);
                          }}
                        >
                          <FileText size={15} />
                          <div className="rail-project-meta">
                            <strong>{doc.title}</strong>
                            <code>已保存批次 {doc.persistedBatchSeq}</code>
                          </div>
                        </button>
                        {canWrite && (
                          <button
                            type="button"
                            className="rail-item-delete-btn"
                            title={`删除文档「${doc.title}」`}
                            aria-label={`删除文档 ${doc.title}`}
                            onClick={() => setPendingDeleteDoc(doc)}
                          >
                            <Trash2 size={13} />
                          </button>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </>
          )}

          {centerMode === "pdf" && (
            <>
              <div className="rail-section-head">
                <h2>PDF 资料 ({pdfFiles.length})</h2>
                <Button
                  className="secondary workbench-btn-xs"
                  onClick={() => setCenterMode("files")}
                >
                  <FolderOpen size={12} /> 上传 PDF
                </Button>
              </div>

              {spaceFilesQuery.isPending && <Loading label="加载 PDF 资料列表…" />}
              {spaceFilesQuery.isError && (
                <div className="alert" role="alert">
                  <AlertCircle size={15} />
                  <span>{featureErrorText(spaceFilesQuery.error)}</span>
                </div>
              )}

              {spaceFilesQuery.isSuccess && pdfFiles.length === 0 && (
                <div className="rail-empty-box">
                  <p className="muted">
                    空间文件区暂无 PDF 资料。请先在「空间文件」中上传 PDF 文件，随后即可在此只读精读并批注引用。
                  </p>
                  <Button
                    className="primary workbench-btn-xs"
                    onClick={() => setCenterMode("files")}
                  >
                    前往空间文件区上传
                  </Button>
                </div>
              )}

              {pdfFiles.length > 0 && (
                <ul className="rail-project-list">
                  {pdfFiles.map((file) => {
                    const isActive =
                      activePdf?.fileId === file.fileId &&
                      activePdf?.fileVersionId === file.sha256;
                    return (
                      <li key={file.fileId}>
                        <button
                          type="button"
                          className={`rail-project-btn ${isActive ? "is-active" : ""}`}
                          aria-current={isActive ? "true" : undefined}
                          onClick={() => {
                            setActivePdf({
                              fileId: file.fileId,
                              fileVersionId: file.sha256,
                              title: file.fileName,
                            });
                            setPdfCommentId(undefined);
                            setPdfFocusBanner(null);
                          }}
                        >
                          <BookOpen size={15} />
                          <div className="rail-project-meta">
                            <strong>{file.fileName}</strong>
                            <code>
                              {formatBytes(file.sizeBytes)} · {shortDigest(file.sha256)}
                            </code>
                          </div>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </>
          )}

          {centerMode === "files" && (
            <div className="rail-empty-box">
              <p className="muted">
                空间文件区继承当前空间成员权限；上传的 PDF 文件可直接切换到「PDF 精读」视图进行选区批注与主群聊引用。
              </p>
              {pdfFiles.length > 0 && (
                <Button
                  className="secondary workbench-btn-xs"
                  onClick={() => setCenterMode("pdf")}
                >
                  <BookOpen size={13} /> 打开 PDF 精读 ({pdfFiles.length})
                </Button>
              )}
            </div>
          )}

          <div className="rail-cli-box">
            <div className="rail-cli-head">
              <span>
                <Terminal size={13} /> CLI 快速接入
              </span>
              <Button
                className="secondary workbench-btn-xs"
                onClick={() => {
                  void copyToClipboard(cliQuickstart).then((ok) => {
                    if (ok) {
                      setCopiedCli(true);
                      window.setTimeout(() => setCopiedCli(false), 2000);
                    }
                  });
                }}
              >
                {copiedCli ? <Check size={12} /> : <Copy size={12} />}
                {copiedCli ? "已复制" : "复制"}
              </Button>
            </div>
            <pre className="cli-code-block">
              <code>{cliQuickstart}</code>
            </pre>
            <Link className="text-link rail-device-link" to="/device">
              前往授权 CLI 设备 →
            </Link>
          </div>
        </aside>

        <section
          className="workbench-center-pane"
          aria-label={
            centerMode === "files"
              ? "空间文件区"
              : centerMode === "docs"
                ? "协作文档工作区"
                : centerMode === "pdf"
                  ? "PDF 资料精读区"
                  : gitSubMode === "remote-settings"
                    ? "工程远端来源设置"
                    : "工程代码画布"
          }
        >
          {centerMode === "files" && (
            <SpaceFilesPanel
              spaceId={spaceId}
              spaceRole={resolvedSpace.role}
              access={access}
              currentUserId={currentUserId}
              highlightedFileId={highlightedFileId}
              onQuoteFileToChat={handleQuoteFileToChat}
            />
          )}

          {centerMode === "docs" &&
            (activeDocument ? (
              <div
                className={`workbench-doc-shell ${docFlashActive ? "workbench-doc-flash" : ""}`}
              >
                {docAnchorBanner &&
                  docAnchorBanner.documentId === activeDocument.documentId && (
                    <div
                      className={`workbench-anchor-banner tone-${
                        docAnchorBanner.status === "ACTIVE"
                          ? "ok"
                          : docAnchorBanner.status === "CONTENT_MODIFIED"
                            ? "warn"
                            : docAnchorBanner.status === "ANCHOR_INVALID" ||
                                docAnchorBanner.status === "ERROR"
                              ? "danger"
                              : "info"
                      }`}
                      role="status"
                    >
                      <div className="workbench-anchor-banner-text">
                        {docAnchorBanner.status === "CONTENT_MODIFIED" ||
                        docAnchorBanner.status === "ANCHOR_INVALID" ? (
                          <AlertTriangle size={15} />
                        ) : docAnchorBanner.status === "ERROR" ? (
                          <AlertCircle size={15} />
                        ) : (
                          <LocateFixed size={15} />
                        )}
                        <span>{docAnchorBanner.message}</span>
                      </div>
                      <div className="workbench-anchor-banner-actions">
                        {docAnchorBanner.view === "CURRENT" ? (
                          <Button
                            className="secondary workbench-btn-xs"
                            onClick={() =>
                              handleOpenDocCard({
                                documentId: docAnchorBanner.documentId,
                                commentId: docAnchorBanner.commentId,
                                persistedVersionId: docAnchorBanner.persistedVersionId,
                                view: "QUOTED_VERSION",
                              })
                            }
                          >
                            <History size={12} /> 查看引用时内容
                          </Button>
                        ) : (
                          <Button
                            className="secondary workbench-btn-xs"
                            onClick={() =>
                              handleOpenDocCard({
                                documentId: docAnchorBanner.documentId,
                                commentId: docAnchorBanner.commentId,
                                persistedVersionId: docAnchorBanner.persistedVersionId,
                                view: "CURRENT",
                              })
                            }
                          >
                            <LocateFixed size={12} /> 查看当前位置
                          </Button>
                        )}
                        <Button
                          className="icon-button secondary"
                          aria-label="关闭定位横幅"
                          onClick={() => setDocAnchorBanner(null)}
                        >
                          <X size={13} />
                        </Button>
                      </div>
                    </div>
                  )}
                <div className="workbench-doc-scroll">
                  <DocumentPanel
                    key={`${activeDocument.documentId}:${docRemountNonce}`}
                    access={access}
                    documentId={activeDocument.documentId}
                    title={activeDocument.title}
                    entry={docEntry}
                    onForward={handleForwardDocOrPdfQuote}
                    summary={aiSummaryOptions}
                  />
                </div>
              </div>
            ) : (
              <div className="workbench-center-empty">
                <span className="empty-mark">
                  <FileText size={32} />
                </span>
                <h3>选择或新建协作文档开始富文本共创</h3>
                <p className="muted">
                  支持多人实时协同编辑、历史版本备份恢复、选区批注双入口引用到右侧空间主群聊，以及经管理员授权后的 AI 辅助摘要。
                </p>
                {canWrite && (
                  <Button
                    className="primary workbench-btn-xs"
                    onClick={() => setShowCreateDocument(true)}
                  >
                    <FilePlus2 size={14} /> 新建协作文档
                  </Button>
                )}
              </div>
            ))}

          {centerMode === "pdf" &&
            (activePdf ? (
              <div
                className={`workbench-doc-shell ${pdfFlashActive ? "workbench-pdf-flash" : ""}`}
              >
                {pdfFocusBanner && (
                  <div className="workbench-anchor-banner tone-ok" role="status">
                    <div className="workbench-anchor-banner-text">
                      <BookOpen size={15} />
                      <span>{pdfFocusBanner}</span>
                    </div>
                    <Button
                      className="icon-button secondary"
                      aria-label="关闭定位提示"
                      onClick={() => setPdfFocusBanner(null)}
                    >
                      <X size={13} />
                    </Button>
                  </div>
                )}
                <div className="workbench-doc-scroll">
                  <PdfReaderPanel
                    key={`${activePdf.fileId}:${activePdf.fileVersionId}:${pdfRemountNonce}`}
                    access={access}
                    fileId={activePdf.fileId}
                    fileVersionId={activePdf.fileVersionId}
                    title={activePdf.title}
                    readOnly={!canWrite}
                    commentId={pdfCommentId}
                    onForward={handleForwardDocOrPdfQuote}
                  />
                </div>
              </div>
            ) : (
              <div className="workbench-center-empty">
                <span className="empty-mark">
                  <BookOpen size={32} />
                </span>
                <h3>选择空间内的 PDF 资料进行只读精读</h3>
                <p className="muted">
                  PDF 资料以只读模式安全渲染，支持文本/矩形选区批注并引用至右侧空间主群聊精准定位。
                </p>
                <Button
                  className="secondary workbench-btn-xs"
                  onClick={() => setCenterMode("files")}
                >
                  <FolderOpen size={14} /> 前往空间文件区上传 PDF
                </Button>
              </div>
            ))}

          {centerMode === "git" &&
            (activeProject ? (
              gitSubMode === "remote-settings" ? (
                <RemoteGitSettingsPanel
                  spaceId={spaceId}
                  projectId={activeProject.projectId}
                  projectName={activeProject.name}
                  spaceRole={resolvedSpace.role}
                  access={access}
                  onBackToCanvas={() => setGitSubMode("canvas")}
                />
              ) : (
                <GitWorkspaceView
                  spaceId={spaceId}
                  projectId={activeProject.projectId}
                  canUploadOrManage={canWrite}
                  focusTarget={focusTarget}
                  commentFocusTarget={commentFocusTarget}
                  onQuoteToChat={handleQuoteToChat}
                  onForwardCodeComment={handleForwardCodeComment}
                />
              )
            ) : (
              <div className="workbench-center-empty">
                <span className="empty-mark">
                  <Orbit size={32} />
                </span>
                <h3>选择或创建 Git 工程以查看代码拓扑</h3>
                <p className="muted">
                  每个空间统一管理 Git 工程、协作文档、PDF 资料与空间文件区，右侧始终保持唯一的空间主群聊。
                </p>
              </div>
            ))}
        </section>

        {/* Keep the space chat mounted when the active view changes. */}
        <ChatPane
          spaceId={spaceId}
          spaceRole={resolvedSpace.role}
          access={access}
          isRevoked={isRevoked}
          currentUserId={currentUserId}
          projects={projects}
          documents={documents}
          activeProjectId={activeProjectId}
          activeResource={activeResource}
          draftQuote={draftQuote}
          externalSyncNonce={chatSyncNonce}
          onClearDraftQuote={() => setDraftQuote(null)}
          onSelectFileQuote={handleQuoteFileToChat}
          onOpenResourceCard={handleOpenResourceCard}
          onOpenCodeCommentCard={handleOpenCodeCommentCard}
          onOpenFileCard={handleOpenFileCard}
          onOpenDocCard={handleOpenDocCard}
          onOpenPdfCard={handleOpenPdfCard}
          onTodoDraftCommitted={handleTodoDraftCommitted}
          onMessagesSnapshot={setChatMessagesSnapshot}
        />
      </div>

      <dialog
        ref={settingsDialogRef}
        className="workbench-settings-dialog"
        aria-label="空间设置、AI 策略与成员管理"
        onCancel={(event) => {
          event.preventDefault();
          setShowSettingsDrawer(false);
        }}
      >
        <div className="workbench-settings-head">
          <h2>空间设置、AI 策略与成员管理</h2>
          <Button
            className="icon-button secondary"
            aria-label="关闭空间设置"
            onClick={() => setShowSettingsDrawer(false)}
          >
            <X size={16} />
          </Button>
        </div>
        {showSettingsDrawer && (
          <SpaceSettingsPanel
            space={resolvedSpace}
            access={access}
            currentUserId={currentUserId}
            onLeftSpace={() => {
              setShowSettingsDrawer(false);
              navigate("/", { replace: true });
            }}
            onSpaceUpdated={() => {
              void spacesQuery.refetch();
              void spaceDetailQuery.refetch();
            }}
          />
        )}
      </dialog>
    </div>
  );
}
