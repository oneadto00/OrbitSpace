import * as Y from "yjs";
import type { DocumentSelection } from "@orbitspace/shared-types";

interface TransformInput {
  operation: "SNAPSHOT" | "RESOLVE" | "DIFF" | "RESTORE" | "MARKDOWN_IMPORT" | "MARKDOWN_EXPORT" | "APPEND";
  updates: string[];
  targetUpdate?: string;
  text?: string;
  selection?: DocumentSelection;
}
type XmlNode = Y.XmlElement | Y.XmlText;
type Relative = ReturnType<typeof Y.createAbsolutePositionFromRelativePosition>;

function children(fragment: Y.XmlFragment): XmlNode[] {
  return fragment.toArray().map(child => {
    if (child instanceof Y.XmlElement || child instanceof Y.XmlText) return child;
    throw new Error("Unsupported document node");
  });
}

function plain(text: Y.XmlText): string {
  return text.toDelta().map((op: { insert: unknown }) => typeof op.insert === "string" ? op.insert : "\uFFFC").join("");
}

function markdown(node: XmlNode): string {
  if (node instanceof Y.XmlText) {
    return node.toDelta().map((op: { insert: unknown; attributes?: Record<string, unknown> }) => {
      let text = String(op.insert);
      if (op.attributes?.bold) text = "**" + text + "**";
      if (op.attributes?.italic) text = "*" + text + "*";
      if (op.attributes?.code) text = "`" + text + "`";
      return text;
    }).join("");
  }
  const parts = children(node).map(markdown);
  switch (node.nodeName) {
    case "heading": return "#".repeat(Math.min(6, Math.max(1, Number(node.getAttribute("level")) || 1))) + " " + parts.join("");
    case "bulletList": return parts.map(p => "- " + p).join("\n");
    case "orderedList": return parts.map((p, i) => (Number(node.getAttribute("start") ?? 1) + i) + ". " + p).join("\n");
    case "blockquote": return parts.join("\n").split("\n").map(p => "> " + p).join("\n");
    case "codeBlock": return "```\n" + parts.join("") + "\n```";
    case "hardBreak": return "\n";
    case "horizontalRule": return "---";
    default: return parts.join("");
  }
}
function paragraph(text: string, name = "paragraph") {
  const p = new Y.XmlElement(name);
  if (text) {
    const run = new Y.XmlText();
    run.insert(0, text);
    p.insert(0, [run]);
  }
  return p;
}
function parseBlocks(text: string, interpretMarkdown: boolean): Y.XmlElement[] {
  const nodes: Y.XmlElement[] = [];
  const lines = text.replace(/\r\n?/g, "\n").split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    const heading = interpretMarkdown && /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      const node = paragraph(heading[2]!, "heading");
      // ProseMirror stores heading levels as JSON numbers.
      const attrs: Y.XmlElement<Record<string, string | number>> = node;
      attrs.setAttribute("level", heading[1]!.length);
      nodes.push(node);
    } else if (interpretMarkdown && line.startsWith("```")) {
      const code: string[] = [];
      while (++i < lines.length && !lines[i]!.startsWith("```")) code.push(lines[i]!);
      nodes.push(paragraph(code.join("\n"), "codeBlock"));
    } else if (interpretMarkdown && /^[-*+]\s+/.test(line)) {
      const list = new Y.XmlElement("bulletList");
      let j = i;
      while (j < lines.length && /^[-*+]\s+/.test(lines[j]!)) {
        const item = new Y.XmlElement("listItem");
        item.insert(0, [paragraph(lines[j]!.replace(/^[-*+]\s+/, ""))]);
        list.insert(j - i, [item]);
        j++;
      }
      i = j - 1; nodes.push(list);
    } else if (interpretMarkdown && line.startsWith("> ")) {
      const block = new Y.XmlElement("blockquote"); block.insert(0, [paragraph(line.slice(2))]); nodes.push(block);
    } else nodes.push(paragraph(line));
  }
  return nodes;
}

// Match ProseMirror textBetween(..., "\n") across text blocks, including
// anchors at an element boundary and UTF-16 offsets inside formatted Y.XmlText.
function selectedText(fragment: Y.XmlFragment, start: Relative, end: Relative) {
  if (!start || !end) return null;
  let offset = 0, from: number | undefined, to: number | undefined, blocks = 0;
  const parts: string[] = [];
  function boundary(node: Y.XmlFragment | Y.XmlText, index: number) {
    if (start?.type === node && start.index === index) from = offset;
    if (end?.type === node && end.index === index) to = offset;
  }
  function visit(node: Y.XmlFragment | Y.XmlText) {
    if (node instanceof Y.XmlText) {
      const str = plain(node);
      if (start?.type === node) from = offset + start.index;
      if (end?.type === node) to = offset + end.index;
      offset += str.length; parts.push(str); return;
    }
    if (node instanceof Y.XmlElement && ["heading","paragraph","codeBlock"].includes(node.nodeName) && blocks++ > 0) {
      parts.push("\n"); offset++;
    }
    const items = children(node);
    items.forEach((item, i) => { boundary(node, i); visit(item); });
    boundary(node, items.length);
  }
  visit(fragment);
  if (from === undefined || to === undefined || from >= to) return null;
  const text = parts.join("");
  return { quote: text.slice(from, to), before: text.slice(0, from), after: text.slice(to) };
}
function decode(value: string) {
  if (typeof value !== "string" || !value.length) throw new Error("Missing encoded state");
  return Buffer.from(value, "base64");
}
const encode = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64");

export function transform(input: TransformInput) {
  const current = new Y.Doc(), target = new Y.Doc();
  try {
    const root = current.getXmlFragment("default");
    if (!Array.isArray(input.updates)) throw new Error("Missing durable state");
    for (const update of input.updates) Y.applyUpdate(current, decode(update));
    const asMarkdown = (fragment: Y.XmlFragment) => children(fragment).map(markdown).join("\n");
    if (input.operation === "SNAPSHOT" || input.operation === "MARKDOWN_EXPORT")
      return { snapshot: encode(Y.encodeStateAsUpdate(current)), text: asMarkdown(root) };
    if (input.operation === "RESOLVE") {
      const selection = input.selection!;
      const resolve = (pos: string) => Y.createAbsolutePositionFromRelativePosition(Y.decodeRelativePosition(decode(pos)), current);
      const text = selectedText(root, resolve(selection.relativeStart), resolve(selection.relativeEnd));
      if (text === null) return { anchorStatus: "ANCHOR_INVALID" };
      const matches = text.quote === selection.quotedText && text.before.endsWith(selection.contextBefore ?? "") &&
        text.after.startsWith(selection.contextAfter ?? "");
      return { anchorStatus: matches ? "ACTIVE" : "CONTENT_MODIFIED" };
    }
    let replacement: XmlNode[];
    if (input.operation === "RESTORE" || input.operation === "DIFF") {
      const targetRoot = target.getXmlFragment("default");
      Y.applyUpdate(target, decode(input.targetUpdate!));
      if (input.operation === "DIFF") {
        const before = asMarkdown(root), after = asMarkdown(targetRoot);
        if (before === after) return { text: "文本及支持的 Markdown 格式相同" };
        const oldLines = before.split("\n"), newLines = after.split("\n");
        let begin = 0, oldEnd = oldLines.length, newEnd = newLines.length;
        while (begin < oldEnd && begin < newEnd && oldLines[begin] === newLines[begin]) begin++;
        while (oldEnd > begin && newEnd > begin && oldLines[oldEnd-1] === newLines[newEnd-1]) { oldEnd--; newEnd--; }
        const lines = ["从第 " + (begin + 1) + " 行开始"];
        for (const line of oldLines.slice(begin, oldEnd)) lines.push("− " + line);
        for (const line of newLines.slice(begin, newEnd)) lines.push("+ " + line);
        return { text: lines.join("\n") };
      }
      replacement = children(targetRoot).map(node => node.clone());
    } else if (input.operation === "APPEND" || input.operation === "MARKDOWN_IMPORT") {
      if (typeof input.text !== "string" || !input.text.length || input.text.length > 4194304) throw new Error("Invalid text");
      replacement = parseBlocks(input.text, input.operation === "MARKDOWN_IMPORT");
    } else throw new Error("Unknown transform");
    const vector = Y.encodeStateVector(current);
    current.transact(() => {
      if (input.operation !== "APPEND") root.delete(0, root.length);
      root.push(replacement);
    });
    return { update: encode(Y.encodeStateAsUpdate(current, vector)) };
  } finally { current.destroy(); target.destroy(); }
}
