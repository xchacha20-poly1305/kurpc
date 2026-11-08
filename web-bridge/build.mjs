import { build } from "esbuild";
import { transformAsync } from "@babel/core";
import presetEnv from "@babel/preset-env";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

export async function bundle() {
    const babelLicense = await readFile("licenses/babel-LICENSE.txt", "utf8");
    return build({
        entryPoints: ["src/index.mjs"], bundle: true, write: false,
        format: "iife", globalName: "kurpcBridge", target: "es2020",
        legalComments: "inline",
        banner: { js: `/*! @license Babel runtime helpers:\n${babelLicense} */` },
    });
}

const out = process.argv[2];
if (out) {
    const result = await bundle();
    const code = result.outputFiles[0].text;
    await mkdir("dist", { recursive: true });
    await writeFile("dist/bridge.js", code);
    // Kotlin's inline-JS parser and printer need ES5 without inline comments.
    // Hoist declarations before returns: the Kotlin optimizer otherwise drops
    // the helpers Babel declares after a return when it lowers async functions.
    const legacy = (await transformAsync(code, {
        presets: [[presetEnv, { targets: { ie: "11" }, modules: false }]],
        sourceType: "script", comments: false, shouldPrintComment: () => false, compact: false,
    })).code;
    const normalized = (await transformAsync(legacy, {
        sourceType: "script", comments: false, shouldPrintComment: () => false,
        plugins: [() => ({ visitor: { BlockStatement(path) {
            const declarations = path.node.body.filter(node => node.type === "FunctionDeclaration");
            const statements = path.node.body.filter(node => node.type !== "FunctionDeclaration");
            path.node.body = [...declarations, ...statements];
        } } })],
    })).code;
    const source = code.slice(0, code.indexOf("*/") + 2) + "\n" + normalized;
    const literal = JSON.stringify(`function() {\n${source}\nreturn kurpcBridge;\n}`).replaceAll("$", "\\$");
    const pkg = "io.github.xchacha20_poly1305.kurpc";
    const dir = resolve(out, ...pkg.split("."));
    await mkdir(dir, { recursive: true });
    await writeFile(resolve(dir, "WebBridgeBundle.kt"), `@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)\n\npackage ${pkg}\n\n@JsFun(${literal})\ninternal external fun loadWebBridge(): WebBridge\n`);
}
