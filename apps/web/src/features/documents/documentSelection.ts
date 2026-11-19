import type { Editor } from "@tiptap/core";
import { createMappablePosition } from "@tiptap/extension-collaboration";
import type { DocumentSelection } from "@orbitspace/shared-types";
import * as Y from "yjs";

export const bytes = (value: string) => Uint8Array.from(atob(value), ch => ch.charCodeAt(0));
export function selection(editor: Editor): DocumentSelection {
  const { state } = editor;
  const { from, to } = state.selection;
  const quotedText = state.doc.textBetween(from, to, "\n");
  if (!quotedText || quotedText.length > 4096) throw new Error("请选择 1 至 4096 字的引用原文。");
  const encode = (pos: number) => {
    const relative = createMappablePosition(pos, state).yRelativePosition;
    if (!relative) throw new Error("选区尚未同步。");
    return btoa(String.fromCharCode(...Y.encodeRelativePosition(relative)));
  };
  return { relativeStart: encode(from), relativeEnd: encode(to), quotedText,
    contextBefore: state.doc.textBetween(Math.max(0, from - 128), from, "\n").slice(-128) || undefined,
    contextAfter: state.doc.textBetween(to, Math.min(to + 128, state.doc.content.size), "\n").slice(0,128) || undefined };
}
function editorOffset(editor: Editor, document: Y.Doc, encoded: string) {
  const target = Y.createAbsolutePositionFromRelativePosition(Y.decodeRelativePosition(bytes(encoded)), document);
  if (!target) return null;
  const state = editor.state;
  let answer: number | null = null;
  function probe(pos: number, length: number) {
    if (answer !== null) return;
    const relative = createMappablePosition(pos, state).yRelativePosition;
    const point = relative && Y.createAbsolutePositionFromRelativePosition(relative, document);
    if (!point || target!.type !== point.type) return;
    const diff = target!.index - point.index;
    if (diff >= 0 && diff <= length) answer = pos + diff;
  }
  state.doc.descendants((node, pos) => {
    probe(pos, node.isText ? node.nodeSize : 0);
    if (node.isText && node.nodeSize > 1) probe(pos + 1, node.nodeSize - 1);
    probe(pos + node.nodeSize, 0);
    return answer === null;
  });
  return answer as number | null;
}
export function locate(editor: Editor, document: Y.Doc, anchor: DocumentSelection) {
  const from = editorOffset(editor, document, anchor.relativeStart), to = editorOffset(editor, document, anchor.relativeEnd);
  if (from === null || to === null || from >= to) return "锚点已失效";
  editor.chain().focus().setTextSelection({ from, to }).scrollIntoView().run();
  return editor.state.doc.textBetween(from, to, "\n") === anchor.quotedText ? "已定位引用原文" : "原文已修改";
}
