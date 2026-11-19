import { mkdir, readFile, writeFile } from "node:fs/promises";
import openapiTS, { astToString } from "openapi-typescript";
import { parse } from "yaml";

const contracts = new URL("../../contracts/", import.meta.url);
const output = new URL("./build/", import.meta.url);
await mkdir(output, { recursive: true });

const domains = {
  Common: "common",
  IdentitySpace: "identity-space",
  Chat: "chat",
  GitProjectCli: "gitproject-cli",
  P3DocsRemoteAi: "p3-docs-remote-ai",
  P2FilesDiff: "p2-files-diff",
};
const index = [];
for (const [domain, file] of Object.entries(domains)) {
  const url = new URL(`openapi/${file}.yaml`, contracts);
  const document = parse(await readFile(url, "utf8"));
  const types = await openapiTS(url);
  await writeFile(new URL(`${file}.ts`, output), astToString(types));

  index.push(`import type { components as ${domain} } from "./${file}.js";`);
  if (domain !== "Common") {
    index.push(`export type { paths as ${domain}Paths, operations as ${domain}Operations } from "./${file}.js";`);
  }
  for (const name of Object.keys(document.components.schemas)) {
    index.push(`export type ${name} = ${domain}["schemas"]["${name}"];`);
  }
  index.push("");
}

// Keep runtime enum values and their wire types tied to the same schema.
const commonTypes = JSON.parse(await readFile(new URL("common.schema.json", contracts), "utf8"));
for (const [name, schema] of Object.entries(commonTypes.$defs)) {
  if (!schema.enum) continue;
  const constants = Object.fromEntries(schema.enum.map((value) => [value, value]));
  index.push(`export const ${name} = ${JSON.stringify(constants, null, 2)} as const satisfies Record<${name}, ${name}>;`, "");
}
await writeFile(new URL("index.ts", output), index.join("\n"));
