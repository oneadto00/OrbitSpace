export type FileChunkHashRequest =
  | {
      type: "HASH_FILE";
      requestId: number;
      file: File;
      partSizeBytes: number;
    }
  | {
      type: "CANCEL";
      requestId: number;
    };

export type FileChunkHashResponse =
  | {
      type: "PROGRESS";
      requestId: number;
      processedBytes: number;
      totalBytes: number;
      completedParts: number;
      totalParts: number;
    }
  | {
      type: "DONE";
      requestId: number;
      fileSha256: string;
      fileSizeBytes: number;
      partSizeBytes: number;
      totalParts: number;
      partSha256s: string[];
    }
  | {
      type: "ERROR";
      requestId: number;
      error: string;
    };

const SHA256_K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

function rotr(value: number, bits: number): number {
  return (value >>> bits) | (value << (32 - bits));
}

function toHexWord(word: number): string {
  return (word >>> 0).toString(16).padStart(8, "0");
}

/** Pure-TypeScript streaming FIPS 180-4 SHA-256 hasher (zero external dependencies, constant RAM). */
class StreamingSha256 {
  private h0 = 0x6a09e667;
  private h1 = 0xbb67ae85;
  private h2 = 0x3c6ef372;
  private h3 = 0xa54ff53a;
  private h4 = 0x510e527f;
  private h5 = 0x9b05688c;
  private h6 = 0x1f83d9ab;
  private h7 = 0x5be0cd19;

  private readonly block = new Uint8Array(64);
  private readonly words = new Uint32Array(64);
  private blockLen = 0;
  private totalBytes = 0;
  private finalized = false;

  update(data: Uint8Array): void {
    if (this.finalized) {
      throw new Error("SHA-256 hasher already finalized");
    }
    const len = data.length;
    if (len === 0) return;

    this.totalBytes += len;
    let offset = 0;

    if (this.blockLen > 0) {
      const needed = 64 - this.blockLen;
      if (len < needed) {
        this.block.set(data, this.blockLen);
        this.blockLen += len;
        return;
      }
      this.block.set(data.subarray(0, needed), this.blockLen);
      this.compress(this.block, 0);
      offset = needed;
      this.blockLen = 0;
    }

    while (offset + 64 <= len) {
      this.compress(data, offset);
      offset += 64;
    }

    if (offset < len) {
      this.block.set(data.subarray(offset), 0);
      this.blockLen = len - offset;
    }
  }

  digestHex(): string {
    if (this.finalized) {
      throw new Error("SHA-256 hasher already finalized");
    }
    this.finalized = true;

    const bitLenHigh = Math.floor(this.totalBytes / 0x20000000) >>> 0;
    const bitLenLow = ((this.totalBytes & 0x1fffffff) << 3) >>> 0;

    this.block[this.blockLen] = 0x80;
    this.blockLen += 1;

    if (this.blockLen > 56) {
      this.block.fill(0, this.blockLen, 64);
      this.compress(this.block, 0);
      this.blockLen = 0;
    }

    this.block.fill(0, this.blockLen, 56);
    this.block[56] = (bitLenHigh >>> 24) & 0xff;
    this.block[57] = (bitLenHigh >>> 16) & 0xff;
    this.block[58] = (bitLenHigh >>> 8) & 0xff;
    this.block[59] = bitLenHigh & 0xff;
    this.block[60] = (bitLenLow >>> 24) & 0xff;
    this.block[61] = (bitLenLow >>> 16) & 0xff;
    this.block[62] = (bitLenLow >>> 8) & 0xff;
    this.block[63] = bitLenLow & 0xff;

    this.compress(this.block, 0);

    return (
      toHexWord(this.h0) +
      toHexWord(this.h1) +
      toHexWord(this.h2) +
      toHexWord(this.h3) +
      toHexWord(this.h4) +
      toHexWord(this.h5) +
      toHexWord(this.h6) +
      toHexWord(this.h7)
    );
  }

  private compress(src: Uint8Array, offset: number): void {
    const w = this.words;
    for (let i = 0; i < 16; i++) {
      const idx = offset + i * 4;
      w[i] =
        (((src[idx] ?? 0) << 24) |
          ((src[idx + 1] ?? 0) << 16) |
          ((src[idx + 2] ?? 0) << 8) |
          (src[idx + 3] ?? 0)) >>>
        0;
    }
    for (let i = 16; i < 64; i++) {
      const w15 = w[i - 15] ?? 0;
      const w2 = w[i - 2] ?? 0;
      const s0 = rotr(w15, 7) ^ rotr(w15, 18) ^ (w15 >>> 3);
      const s1 = rotr(w2, 17) ^ rotr(w2, 19) ^ (w2 >>> 10);
      w[i] = ((w[i - 16] ?? 0) + s0 + (w[i - 7] ?? 0) + s1) >>> 0;
    }

    let a = this.h0;
    let b = this.h1;
    let c = this.h2;
    let d = this.h3;
    let e = this.h4;
    let f = this.h5;
    let g = this.h6;
    let h = this.h7;

    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const temp1 = (h + S1 + ch + (SHA256_K[i] ?? 0) + (w[i] ?? 0)) >>> 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (S0 + maj) >>> 0;

      h = g;
      g = f;
      f = e;
      e = (d + temp1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) >>> 0;
    }

    this.h0 = (this.h0 + a) >>> 0;
    this.h1 = (this.h1 + b) >>> 0;
    this.h2 = (this.h2 + c) >>> 0;
    this.h3 = (this.h3 + d) >>> 0;
    this.h4 = (this.h4 + e) >>> 0;
    this.h5 = (this.h5 + f) >>> 0;
    this.h6 = (this.h6 + g) >>> 0;
    this.h7 = (this.h7 + h) >>> 0;
  }
}

interface WorkerMessageTarget {
  addEventListener: (
    type: "message",
    listener: (event: MessageEvent<FileChunkHashRequest>) => void,
  ) => void;
  postMessage: (message: FileChunkHashResponse) => void;
}

const workerScope = globalThis as unknown as WorkerMessageTarget;
const cancelledRequests = new Set<number>();

async function hashFileInChunks(requestId: number, file: File, rawPartSizeBytes: number): Promise<void> {
  const partSizeBytes = Math.max(1, Math.floor(rawPartSizeBytes));
  const totalBytes = file.size;
  const totalParts = totalBytes === 0 ? 1 : Math.ceil(totalBytes / partSizeBytes);

  const fullHasher = new StreamingSha256();
  const partSha256s: string[] = [];

  if (totalBytes === 0) {
    const emptyDigest = fullHasher.digestHex();
    if (cancelledRequests.has(requestId)) {
      cancelledRequests.delete(requestId);
      return;
    }
    workerScope.postMessage({
      type: "PROGRESS",
      requestId,
      processedBytes: 0,
      totalBytes: 0,
      completedParts: 1,
      totalParts: 1,
    });
    workerScope.postMessage({
      type: "DONE",
      requestId,
      fileSha256: emptyDigest,
      fileSizeBytes: 0,
      partSizeBytes,
      totalParts: 1,
      partSha256s: [emptyDigest],
    });
    return;
  }

  let processedBytes = 0;
  for (let partIndex = 0; partIndex < totalParts; partIndex++) {
    if (cancelledRequests.has(requestId)) {
      cancelledRequests.delete(requestId);
      return;
    }

    const start = partIndex * partSizeBytes;
    const end = Math.min(totalBytes, start + partSizeBytes);
    const buffer = await file.slice(start, end).arrayBuffer();

    if (cancelledRequests.has(requestId)) {
      cancelledRequests.delete(requestId);
      return;
    }

    const bytes = new Uint8Array(buffer);
    const partHasher = new StreamingSha256();
    partHasher.update(bytes);
    partSha256s.push(partHasher.digestHex());

    fullHasher.update(bytes);
    processedBytes = end;

    workerScope.postMessage({
      type: "PROGRESS",
      requestId,
      processedBytes,
      totalBytes,
      completedParts: partIndex + 1,
      totalParts,
    });
  }

  if (cancelledRequests.has(requestId)) {
    cancelledRequests.delete(requestId);
    return;
  }

  workerScope.postMessage({
    type: "DONE",
    requestId,
    fileSha256: fullHasher.digestHex(),
    fileSizeBytes: totalBytes,
    partSizeBytes,
    totalParts,
    partSha256s,
  });
}

workerScope.addEventListener("message", (event: MessageEvent<FileChunkHashRequest>) => {
  const payload = event.data;
  if (payload.type === "CANCEL") {
    cancelledRequests.add(payload.requestId);
    return;
  }

  const { requestId, file, partSizeBytes } = payload;
  cancelledRequests.delete(requestId);
  void hashFileInChunks(requestId, file, partSizeBytes).catch((err: unknown) => {
    cancelledRequests.delete(requestId);
    const message = err instanceof Error ? err.message : "文件分片哈希计算失败";
    workerScope.postMessage({
      type: "ERROR",
      requestId,
      error: message,
    });
  });
});
