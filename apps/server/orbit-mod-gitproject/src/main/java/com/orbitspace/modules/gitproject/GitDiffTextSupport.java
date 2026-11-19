package com.orbitspace.modules.gitproject;

import com.orbitspace.modules.gitproject.GitDiffCommentDtos.CodeCommentSide;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text diffing, unified patch parsing/inversion/application, and bounded code excerpt
 * extraction for P2 controlled two-point diffs and line-anchored code comments.
 */
final class GitDiffTextSupport {
    private static final int CONTEXT_LINES = 3;
    private static final int MAX_MYERS_LINES = 2000;
    private static final int MAX_MYERS_D = 1000;
    static final int MAX_STORED_PATCH_CHARS = 262_144;
    static final int MAX_EXCERPT_CHARS = 1800;

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@(.*)$");

    private GitDiffTextSupport() {}

    record ParsedHunk(
            int oldStart, int oldCount, int newStart, int newCount, String heading, List<String> lines) {}

    record ParsedPatchFile(
            String path,
            String oldPath,
            boolean newFile,
            boolean deletedFile,
            boolean binary,
            String patchText,
            List<ParsedHunk> hunks) {}

    private enum EditOp {
        EQUAL,
        DELETE,
        INSERT
    }

    private record Edit(EditOp op, int oldIndex, int newIndex) {}

    /** Decodes raw bytes strictly as UTF-8 text, rejecting NUL bytes and malformed sequences. */
    static Optional<String> decodeUtf8Text(byte[] bytes) {
        if (bytes == null) {
            return Optional.empty();
        }
        for (byte b : bytes) {
            if (b == 0) {
                return Optional.empty();
            }
        }
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return Optional.of(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException ex) {
            return Optional.empty();
        }
    }

    /** Splits text into normalized lines without trailing line terminators. */
    static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        int start = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                int end = (i > start && text.charAt(i - 1) == '\r') ? i - 1 : i;
                lines.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start < len) {
            int end = (text.charAt(len - 1) == '\r') ? len - 1 : len;
            lines.add(text.substring(start, end));
        }
        return lines;
    }

    /** Computes a standard unified diff for ADDED, DELETED, or MODIFIED text files. */
    static String unifiedDiff(String path, String oldText, String newText) {
        if (oldText == null && newText == null) {
            return null;
        }
        if (oldText == null) {
            List<String> newLines = splitLines(newText);
            StringBuilder sb = new StringBuilder();
            sb.append("--- /dev/null\n");
            sb.append("+++ b/").append(path).append('\n');
            if (newLines.isEmpty()) {
                sb.append("@@ -0,0 +0,0 @@\n");
            } else {
                sb.append("@@ -0,0 +1,").append(newLines.size()).append(" @@\n");
                for (String line : newLines) {
                    sb.append('+').append(line).append('\n');
                }
            }
            return boundPatchText(sb.toString());
        }
        if (newText == null) {
            List<String> oldLines = splitLines(oldText);
            StringBuilder sb = new StringBuilder();
            sb.append("--- a/").append(path).append('\n');
            sb.append("+++ /dev/null\n");
            if (oldLines.isEmpty()) {
                sb.append("@@ -0,0 +0,0 @@\n");
            } else {
                sb.append("@@ -1,").append(oldLines.size()).append(" +0,0 @@\n");
                for (String line : oldLines) {
                    sb.append('-').append(line).append('\n');
                }
            }
            return boundPatchText(sb.toString());
        }

        List<String> oldLines = splitLines(oldText);
        List<String> newLines = splitLines(newText);
        if (oldLines.equals(newLines)) {
            return null;
        }

        List<Edit> edits = computeEdits(oldLines, newLines);
        String rendered = renderUnifiedDiff(path, oldLines, newLines, edits);
        return boundPatchText(rendered);
    }

    /** Caps stored patch text cleanly at a newline boundary to avoid huge DB JSON rows. */
    static String boundPatchText(String patchText) {
        if (patchText == null || patchText.length() <= MAX_STORED_PATCH_CHARS) {
            return patchText;
        }
        int cut = patchText.lastIndexOf('\n', MAX_STORED_PATCH_CHARS - 64);
        if (cut < 0) {
            cut = MAX_STORED_PATCH_CHARS - 64;
        }
        return patchText.substring(0, cut) + "\n\\ Diff output truncated at storage bound\n";
    }

    /** Parses a multi-file git unified diff patch into per-file sections keyed by target path. */
    static Map<String, ParsedPatchFile> parseUnifiedPatch(String rawPatch) {
        if (rawPatch == null || rawPatch.isEmpty()) {
            return Map.of();
        }
        List<String> allLines = splitLines(rawPatch);
        Map<String, ParsedPatchFile> result = new LinkedHashMap<>();

        List<String> sectionLines = null;
        for (String line : allLines) {
            if (line.startsWith("diff --git ")) {
                flushPatchSection(sectionLines, result);
                sectionLines = new ArrayList<>();
                sectionLines.add(line);
            } else {
                if (sectionLines == null) {
                    if (line.startsWith("--- ")) {
                        sectionLines = new ArrayList<>();
                        sectionLines.add(line);
                    }
                } else {
                    sectionLines.add(line);
                }
            }
        }
        flushPatchSection(sectionLines, result);
        return result;
    }

    /** Extracts full new-file text when a patch section creates a file from /dev/null. */
    static Optional<String> extractNewFileText(ParsedPatchFile file) {
        if (file == null || !file.newFile() || file.binary()) {
            return Optional.empty();
        }
        if (file.hunks().isEmpty()) {
            return Optional.of("");
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ParsedHunk hunk : file.hunks()) {
            for (String line : hunk.lines()) {
                if (line.startsWith("+")) {
                    if (!first) {
                        sb.append('\n');
                    }
                    sb.append(line.substring(1));
                    first = false;
                }
            }
        }
        if (!first) {
            sb.append('\n');
        }
        return Optional.of(sb.toString());
    }

    /** Applies a parsed unified diff section strictly onto baseText; returns empty on mismatch. */
    static Optional<String> applyPatchToBase(String baseText, ParsedPatchFile file) {
        if (file == null || file.binary() || file.deletedFile()) {
            return Optional.empty();
        }
        if (file.newFile()) {
            return extractNewFileText(file);
        }
        if (baseText == null) {
            return Optional.empty();
        }
        if (file.hunks().isEmpty()) {
            return Optional.of(baseText);
        }

        List<String> baseLines = splitLines(baseText);
        List<String> out = new ArrayList<>(baseLines.size());
        int cursor = 0;

        for (ParsedHunk hunk : file.hunks()) {
            int targetIdx = hunk.oldCount() == 0 ? hunk.oldStart() : Math.max(0, hunk.oldStart() - 1);
            if (targetIdx < cursor || targetIdx > baseLines.size()) {
                return Optional.empty();
            }
            while (cursor < targetIdx) {
                out.add(baseLines.get(cursor++));
            }
            for (String line : hunk.lines()) {
                if (line.startsWith("\\")) {
                    continue;
                }
                if (line.startsWith(" ")) {
                    String expected = line.substring(1);
                    if (cursor >= baseLines.size() || !baseLines.get(cursor).equals(expected)) {
                        return Optional.empty();
                    }
                    out.add(baseLines.get(cursor++));
                } else if (line.startsWith("-")) {
                    String expected = line.substring(1);
                    if (cursor >= baseLines.size() || !baseLines.get(cursor).equals(expected)) {
                        return Optional.empty();
                    }
                    cursor++;
                } else if (line.startsWith("+")) {
                    out.add(line.substring(1));
                }
            }
        }
        while (cursor < baseLines.size()) {
            out.add(baseLines.get(cursor++));
        }

        if (out.isEmpty()) {
            return Optional.of("");
        }
        return Optional.of(String.join("\n", out) + "\n");
    }

    /** Produces the reversed unified diff (target -> base) for a parsed file patch. */
    static String invertPatch(ParsedPatchFile file) {
        if (file == null || file.binary()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String effectiveOld = file.oldPath() != null ? file.oldPath() : file.path();
        if (file.deletedFile()) {
            sb.append("--- /dev/null\n");
            sb.append("+++ b/").append(effectiveOld).append('\n');
        } else if (file.newFile()) {
            sb.append("--- a/").append(file.path()).append('\n');
            sb.append("+++ /dev/null\n");
        } else {
            sb.append("--- a/").append(file.path()).append('\n');
            sb.append("+++ b/").append(effectiveOld).append('\n');
        }

        for (ParsedHunk hunk : file.hunks()) {
            sb.append("@@ -")
                    .append(hunk.newStart())
                    .append(',')
                    .append(hunk.newCount())
                    .append(" +")
                    .append(hunk.oldStart())
                    .append(',')
                    .append(hunk.oldCount())
                    .append(" @@");
            if (hunk.heading() != null && !hunk.heading().isEmpty()) {
                sb.append(hunk.heading());
            }
            sb.append('\n');

            List<String> pendingDels = new ArrayList<>();
            List<String> pendingAdds = new ArrayList<>();
            for (String line : hunk.lines()) {
                if (line.startsWith("+")) {
                    pendingDels.add("-" + line.substring(1));
                } else if (line.startsWith("-")) {
                    pendingAdds.add("+" + line.substring(1));
                } else {
                    flushPendingInverted(sb, pendingDels, pendingAdds);
                    sb.append(line).append('\n');
                }
            }
            flushPendingInverted(sb, pendingDels, pendingAdds);
        }
        return boundPatchText(sb.toString());
    }

    /** Extracts a small bounded code context window around (side, lineNumber) from unified diff text. */
    static String extractContextExcerpt(String patchText, CodeCommentSide side, int lineNumber) {
        if (patchText == null || patchText.isBlank() || side == null || lineNumber < 1) {
            return null;
        }
        List<String> lines = splitLines(patchText);
        List<String> hunkBodyLines = new ArrayList<>();
        int matchIndex = -1;

        boolean inHunk = false;
        int oldCursor = 1;
        int newCursor = 1;
        int oldRemaining = 0;
        int newRemaining = 0;

        for (String line : lines) {
            Matcher m = HUNK_HEADER.matcher(line);
            if (m.matches()) {
                inHunk = true;
                oldCursor = Integer.parseInt(m.group(1));
                oldRemaining = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
                newCursor = Integer.parseInt(m.group(3));
                newRemaining = m.group(4) != null ? Integer.parseInt(m.group(4)) : 1;
                continue;
            }
            if (!inHunk) {
                continue;
            }
            if (line.startsWith("\\")) {
                continue;
            }
            if (oldRemaining <= 0 && newRemaining <= 0) {
                inHunk = false;
                continue;
            }
            if (line.startsWith("+")) {
                int idx = hunkBodyLines.size();
                hunkBodyLines.add(line);
                if (matchIndex < 0 && side == CodeCommentSide.NEW && newCursor == lineNumber) {
                    matchIndex = idx;
                }
                newCursor++;
                if (newRemaining > 0) newRemaining--;
            } else if (line.startsWith("-")) {
                int idx = hunkBodyLines.size();
                hunkBodyLines.add(line);
                if (matchIndex < 0 && side == CodeCommentSide.OLD && oldCursor == lineNumber) {
                    matchIndex = idx;
                }
                oldCursor++;
                if (oldRemaining > 0) oldRemaining--;
            } else if (line.startsWith(" ") || line.isEmpty()) {
                int idx = hunkBodyLines.size();
                hunkBodyLines.add(line.isEmpty() ? " " : line);
                if (matchIndex < 0
                        && ((side == CodeCommentSide.OLD && oldCursor == lineNumber)
                                || (side == CodeCommentSide.NEW && newCursor == lineNumber))) {
                    matchIndex = idx;
                }
                oldCursor++;
                newCursor++;
                if (oldRemaining > 0) oldRemaining--;
                if (newRemaining > 0) newRemaining--;
            } else {
                inHunk = false;
            }
        }

        if (matchIndex < 0) {
            return null;
        }
        int from = Math.max(0, matchIndex - 2);
        int to = Math.min(hunkBodyLines.size(), matchIndex + 3);
        StringBuilder excerpt = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                excerpt.append('\n');
            }
            excerpt.append(hunkBodyLines.get(i));
        }
        String text = excerpt.toString().strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_EXCERPT_CHARS) {
            return text.substring(0, MAX_EXCERPT_CHARS);
        }
        return text;
    }

    private static void flushPendingInverted(
            StringBuilder sb, List<String> pendingDels, List<String> pendingAdds) {
        for (String d : pendingDels) {
            sb.append(d).append('\n');
        }
        for (String a : pendingAdds) {
            sb.append(a).append('\n');
        }
        pendingDels.clear();
        pendingAdds.clear();
    }

    private static void flushPatchSection(
            List<String> lines, Map<String, ParsedPatchFile> out) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        String aPath = null;
        String bPath = null;
        String renameFrom = null;
        String renameTo = null;
        boolean newFile = false;
        boolean deletedFile = false;
        boolean binary = false;

        List<ParsedHunk> hunks = new ArrayList<>();
        int curOldStart = 0;
        int curOldCount = 0;
        int curNewStart = 0;
        int curNewCount = 0;
        String curHeading = "";
        List<String> curHunkLines = null;
        int oldRem = 0;
        int newRem = 0;

        for (String line : lines) {
            if (curHunkLines != null) {
                Matcher hm = HUNK_HEADER.matcher(line);
                if (hm.matches()) {
                    hunks.add(
                            new ParsedHunk(
                                    curOldStart,
                                    curOldCount,
                                    curNewStart,
                                    curNewCount,
                                    curHeading,
                                    List.copyOf(curHunkLines)));
                    curOldStart = Integer.parseInt(hm.group(1));
                    curOldCount = hm.group(2) != null ? Integer.parseInt(hm.group(2)) : 1;
                    curNewStart = Integer.parseInt(hm.group(3));
                    curNewCount = hm.group(4) != null ? Integer.parseInt(hm.group(4)) : 1;
                    curHeading = hm.group(5) != null ? hm.group(5) : "";
                    curHunkLines = new ArrayList<>();
                    oldRem = curOldCount;
                    newRem = curNewCount;
                    continue;
                }
                if (line.startsWith("\\")) {
                    curHunkLines.add(line);
                    continue;
                }
                if (oldRem > 0 || newRem > 0) {
                    if (line.startsWith("+")) {
                        curHunkLines.add(line);
                        if (newRem > 0) newRem--;
                        continue;
                    }
                    if (line.startsWith("-")) {
                        curHunkLines.add(line);
                        if (oldRem > 0) oldRem--;
                        continue;
                    }
                    if (line.startsWith(" ") || line.isEmpty()) {
                        curHunkLines.add(line.isEmpty() ? " " : line);
                        if (oldRem > 0) oldRem--;
                        if (newRem > 0) newRem--;
                        continue;
                    }
                }
                hunks.add(
                        new ParsedHunk(
                                curOldStart,
                                curOldCount,
                                curNewStart,
                                curNewCount,
                                curHeading,
                                List.copyOf(curHunkLines)));
                curHunkLines = null;
            }

            if (line.startsWith("diff --git ")) {
                String[] pair = extractDiffGitPair(line);
                if (pair != null) {
                    aPath = pair[0];
                    bPath = pair[1];
                }
            } else if (line.startsWith("new file mode ")) {
                newFile = true;
            } else if (line.startsWith("deleted file mode ")) {
                deletedFile = true;
            } else if (line.startsWith("rename from ")) {
                renameFrom = cleanPathToken(line.substring("rename from ".length()));
            } else if (line.startsWith("rename to ")) {
                renameTo = cleanPathToken(line.substring("rename to ".length()));
            } else if (line.startsWith("Binary files ") || line.equals("GIT binary patch")) {
                binary = true;
            } else if (line.startsWith("--- ")) {
                String raw = stripTabMeta(line.substring(4));
                if ("/dev/null".equals(raw)) {
                    newFile = true;
                } else {
                    aPath = stripPrefix(cleanPathToken(raw), "a/");
                }
            } else if (line.startsWith("+++ ")) {
                String raw = stripTabMeta(line.substring(4));
                if ("/dev/null".equals(raw)) {
                    deletedFile = true;
                } else {
                    bPath = stripPrefix(cleanPathToken(raw), "b/");
                }
            } else {
                Matcher hm = HUNK_HEADER.matcher(line);
                if (hm.matches()) {
                    curOldStart = Integer.parseInt(hm.group(1));
                    curOldCount = hm.group(2) != null ? Integer.parseInt(hm.group(2)) : 1;
                    curNewStart = Integer.parseInt(hm.group(3));
                    curNewCount = hm.group(4) != null ? Integer.parseInt(hm.group(4)) : 1;
                    curHeading = hm.group(5) != null ? hm.group(5) : "";
                    curHunkLines = new ArrayList<>();
                    oldRem = curOldCount;
                    newRem = curNewCount;
                }
            }
        }

        if (curHunkLines != null) {
            hunks.add(
                    new ParsedHunk(
                            curOldStart,
                            curOldCount,
                            curNewStart,
                            curNewCount,
                            curHeading,
                            List.copyOf(curHunkLines)));
        }

        String primaryPath =
                renameTo != null
                        ? renameTo
                        : (bPath != null ? bPath : (aPath != null ? aPath : renameFrom));
        if (primaryPath == null || !GitProjectUtils.isSafeRelativePath(primaryPath)) {
            return;
        }
        String resolvedOldPath = null;
        String candidateOld = renameFrom != null ? renameFrom : aPath;
        if (candidateOld != null
                && !candidateOld.equals(primaryPath)
                && GitProjectUtils.isSafeRelativePath(candidateOld)) {
            resolvedOldPath = candidateOld;
        }
        String rawSection = String.join("\n", lines) + "\n";
        out.put(
                primaryPath,
                new ParsedPatchFile(
                        primaryPath,
                        resolvedOldPath,
                        newFile,
                        deletedFile,
                        binary,
                        boundPatchText(rawSection),
                        List.copyOf(hunks)));
    }

    private static String[] extractDiffGitPair(String line) {
        String rest = line.substring("diff --git ".length()).strip();
        int split = rest.indexOf(" b/");
        if (split > 0 && rest.startsWith("a/")) {
            String left = cleanPathToken(rest.substring(2, split));
            String right = cleanPathToken(rest.substring(split + 3));
            return new String[] {left, right};
        }
        return null;
    }

    private static String stripTabMeta(String value) {
        int tab = value.indexOf('\t');
        return (tab >= 0 ? value.substring(0, tab) : value).strip();
    }

    private static String cleanPathToken(String token) {
        String s = token.strip();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static List<Edit> computeEdits(List<String> oldLines, List<String> newLines) {
        int n = oldLines.size();
        int m = newLines.size();

        int prefix = 0;
        while (prefix < n && prefix < m && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < (n - prefix)
                && suffix < (m - prefix)
                && oldLines.get(n - 1 - suffix).equals(newLines.get(m - 1 - suffix))) {
            suffix++;
        }

        List<String> oldMid = oldLines.subList(prefix, n - suffix);
        List<String> newMid = newLines.subList(prefix, m - suffix);

        List<Edit> result = new ArrayList<>(n + m);
        for (int i = 0; i < prefix; i++) {
            result.add(new Edit(EditOp.EQUAL, i, i));
        }

        List<Edit> midEdits = myersEdits(oldMid, newMid);
        for (Edit e : midEdits) {
            result.add(
                    new Edit(
                            e.op(),
                            e.oldIndex() >= 0 ? e.oldIndex() + prefix : -1,
                            e.newIndex() >= 0 ? e.newIndex() + prefix : -1));
        }

        int oldSuffixStart = n - suffix;
        int newSuffixStart = m - suffix;
        for (int i = 0; i < suffix; i++) {
            result.add(new Edit(EditOp.EQUAL, oldSuffixStart + i, newSuffixStart + i));
        }
        return result;
    }

    private static List<Edit> myersEdits(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        if (n == 0) {
            List<Edit> ins = new ArrayList<>(m);
            for (int j = 0; j < m; j++) {
                ins.add(new Edit(EditOp.INSERT, -1, j));
            }
            return ins;
        }
        if (m == 0) {
            List<Edit> dels = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                dels.add(new Edit(EditOp.DELETE, i, -1));
            }
            return dels;
        }
        if (n + m > MAX_MYERS_LINES) {
            return fallbackReplaceEdits(n, m);
        }

        int maxD = Math.max(1, Math.min(n + m, MAX_MYERS_D));
        int offset = maxD;
        int[] v = new int[2 * maxD + 2];
        v[offset + 1] = 0;

        List<int[]> trace = new ArrayList<>();
        boolean reached = false;

        for (int d = 0; d <= maxD; d++) {
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1];
                } else {
                    x = v[offset + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    reached = true;
                    break;
                }
            }
            int[] snap = new int[2 * d + 1];
            System.arraycopy(v, offset - d, snap, 0, 2 * d + 1);
            trace.add(snap);
            if (reached) {
                break;
            }
        }

        if (!reached) {
            return fallbackReplaceEdits(n, m);
        }

        List<Edit> reversed = new ArrayList<>(n + m);
        int x = n;
        int y = m;
        for (int d = trace.size() - 1; d > 0; d--) {
            int[] prev = trace.get(d - 1);
            int prevOffset = d - 1;
            int k = x - y;
            int prevK;
            if (k == -d
                    || (k != d
                            && prev[prevOffset + k - 1] < prev[prevOffset + k + 1])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = prev[prevOffset + prevK];
            int prevY = prevX - prevK;

            while (x > prevX && y > prevY) {
                x--;
                y--;
                reversed.add(new Edit(EditOp.EQUAL, x, y));
            }
            if (x == prevX) {
                y--;
                reversed.add(new Edit(EditOp.INSERT, -1, y));
            } else {
                x--;
                reversed.add(new Edit(EditOp.DELETE, x, -1));
            }
        }
        while (x > 0 && y > 0) {
            x--;
            y--;
            reversed.add(new Edit(EditOp.EQUAL, x, y));
        }

        Collections.reverse(reversed);
        return reversed;
    }

    private static List<Edit> fallbackReplaceEdits(int n, int m) {
        List<Edit> edits = new ArrayList<>(n + m);
        for (int i = 0; i < n; i++) {
            edits.add(new Edit(EditOp.DELETE, i, -1));
        }
        for (int j = 0; j < m; j++) {
            edits.add(new Edit(EditOp.INSERT, -1, j));
        }
        return edits;
    }

    private static String renderUnifiedDiff(
            String path, List<String> oldLines, List<String> newLines, List<Edit> edits) {
        List<int[]> changeRanges = new ArrayList<>();
        int idx = 0;
        while (idx < edits.size()) {
            if (edits.get(idx).op() == EditOp.EQUAL) {
                idx++;
                continue;
            }
            int start = Math.max(0, idx - CONTEXT_LINES);
            int end = idx;
            while (end < edits.size()) {
                if (edits.get(end).op() != EditOp.EQUAL) {
                    end++;
                } else {
                    int eqRun = 0;
                    while (end + eqRun < edits.size()
                            && edits.get(end + eqRun).op() == EditOp.EQUAL) {
                        eqRun++;
                    }
                    if (end + eqRun < edits.size() && eqRun <= CONTEXT_LINES * 2) {
                        end += eqRun;
                    } else {
                        end = Math.min(edits.size(), end + CONTEXT_LINES);
                        break;
                    }
                }
            }
            changeRanges.add(new int[] {start, end});
            idx = end;
        }

        if (changeRanges.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(path).append('\n');
        sb.append("+++ b/").append(path).append('\n');

        for (int[] range : changeRanges) {
            int start = range[0];
            int end = range[1];
            int oldCount = 0;
            int newCount = 0;
            int firstOld = -1;
            int firstNew = -1;

            for (int i = start; i < end; i++) {
                Edit e = edits.get(i);
                switch (e.op()) {
                    case EQUAL -> {
                        if (firstOld < 0) firstOld = e.oldIndex();
                        if (firstNew < 0) firstNew = e.newIndex();
                        oldCount++;
                        newCount++;
                    }
                    case DELETE -> {
                        if (firstOld < 0) firstOld = e.oldIndex();
                        oldCount++;
                    }
                    case INSERT -> {
                        if (firstNew < 0) firstNew = e.newIndex();
                        newCount++;
                    }
                }
            }

            int oldStart = oldCount == 0 ? previousOldLineCount(edits, start) : firstOld + 1;
            int newStart = newCount == 0 ? previousNewLineCount(edits, start) : firstNew + 1;
            sb.append("@@ -")
                    .append(oldStart)
                    .append(',')
                    .append(oldCount)
                    .append(" +")
                    .append(newStart)
                    .append(',')
                    .append(newCount)
                    .append(" @@\n");

            for (int i = start; i < end; i++) {
                Edit e = edits.get(i);
                switch (e.op()) {
                    case EQUAL -> sb.append(' ').append(oldLines.get(e.oldIndex())).append('\n');
                    case DELETE -> sb.append('-').append(oldLines.get(e.oldIndex())).append('\n');
                    case INSERT -> sb.append('+').append(newLines.get(e.newIndex())).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static int previousOldLineCount(List<Edit> edits, int beforeIndex) {
        int count = 0;
        for (int i = 0; i < beforeIndex; i++) {
            EditOp op = edits.get(i).op();
            if (op == EditOp.EQUAL || op == EditOp.DELETE) {
                count++;
            }
        }
        return count;
    }

    private static int previousNewLineCount(List<Edit> edits, int beforeIndex) {
        int count = 0;
        for (int i = 0; i < beforeIndex; i++) {
            EditOp op = edits.get(i).op();
            if (op == EditOp.EQUAL || op == EditOp.INSERT) {
                count++;
            }
        }
        return count;
    }
}
