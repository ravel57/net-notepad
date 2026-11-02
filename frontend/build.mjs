import * as esbuild from "esbuild";
import {rmSync} from "node:fs";

const outdir = "../build/generated/frontend/static/js";
rmSync(outdir, {recursive: true, force: true});

await esbuild.build({
	entryPoints: ["src/app.js", "src/vendor.css"],
	outdir,
	bundle: true,
	minify: true,
	sourcemap: true,
	format: "esm",
	// Mermaid, KaTeX and the QR code generator are loaded only when needed
	splitting: true,
	target: "es2020",
	entryNames: "[name]",
	chunkNames: "chunks/[name]-[hash]",
	assetNames: "assets/[name]-[hash]",
	loader: {".woff2": "file", ".woff": "file", ".ttf": "file"},
	logLevel: "info",
});
