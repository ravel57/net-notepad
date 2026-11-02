import {Marked} from "marked";
import {markedHighlight} from "marked-highlight";
import createDOMPurify from "dompurify";
import hljs from "highlight.js/lib/core";
import bash from "highlight.js/lib/languages/bash";
import css from "highlight.js/lib/languages/css";
import java from "highlight.js/lib/languages/java";
import javascript from "highlight.js/lib/languages/javascript";
import json from "highlight.js/lib/languages/json";
import kotlin from "highlight.js/lib/languages/kotlin";
import markdown from "highlight.js/lib/languages/markdown";
import python from "highlight.js/lib/languages/python";
import sql from "highlight.js/lib/languages/sql";
import typescript from "highlight.js/lib/languages/typescript";
import xml from "highlight.js/lib/languages/xml";
import yaml from "highlight.js/lib/languages/yaml";

Object.entries({bash, css, java, javascript, json, kotlin, markdown, python, sql, typescript, xml, yaml})
	.forEach(([name, language]) => hljs.registerLanguage(name, language));

const marked = new Marked({gfm: true}, markedHighlight({
	emptyLangClass: "hljs",
	langPrefix: "hljs language-",
	highlight: (code, lang) => hljs.getLanguage(lang) ? hljs.highlight(code, {language: lang}).value : code,
}));

// A private instance: Mermaid adds and removes hooks on the shared one
const purify = createDOMPurify(window);
purify.addHook("afterSanitizeAttributes", node => {
	if (node.tagName === "A" && node.getAttribute("href")) {
		node.setAttribute("target", "_blank");
		node.setAttribute("rel", "noopener noreferrer");
	}
});

let katexState = "none";
let mermaidPromise = null;
let dark = false;
const diagrams = new Map();

export function setPreviewDark(value) {
	if (dark === value) return;
	dark = value;
	diagrams.clear();
}

/** Renders Markdown into [container]; [rerender] is called when lazily loaded extensions become available. */
export function renderMarkdown(container, text, rerender) {
	if (katexState === "none" && text.includes("$")) {
		katexState = "loading";
		import("marked-katex-extension").then(({default: markedKatex}) => {
			marked.use(markedKatex({throwOnError: false, output: "html", nonStandard: true}));
			katexState = "ready";
			rerender();
		});
	}
	container.innerHTML = purify.sanitize(marked.parse(text));
	renderDiagrams(container);
}

function renderDiagrams(container) {
	for (const code of container.querySelectorAll("pre > code.language-mermaid")) {
		const source = code.textContent;
		const holder = document.createElement("div");
		holder.className = "mermaid-diagram";
		code.parentElement.replaceWith(holder);

		let diagram = diagrams.get(source);
		if (!diagram) {
			diagram = {promise: renderDiagram(source)};
			diagram.promise.then(svg => diagram.svg = svg, error => diagram.error = error.message ?? String(error));
			diagrams.set(source, diagram);
			if (diagrams.size > 50) diagrams.delete(diagrams.keys().next().value);
		}
		show(holder, diagram);
		if (diagram.svg === undefined && diagram.error === undefined) {
			diagram.promise.finally(() => holder.isConnected && show(holder, diagram));
		}
	}
}

function show(holder, diagram) {
	holder.classList.toggle("error", diagram.error !== undefined);
	if (diagram.svg !== undefined) {
		holder.innerHTML = diagram.svg;
	} else {
		holder.textContent = diagram.error !== undefined ? `Ошибка в диаграмме: ${diagram.error}` : "Отрисовка диаграммы…";
	}
}

let diagramCounter = 0;

async function renderDiagram(source) {
	mermaidPromise ??= import("mermaid").then(module => module.default);
	const mermaid = await mermaidPromise;
	mermaid.initialize({startOnLoad: false, securityLevel: "strict", theme: dark ? "dark" : "default"});
	const {svg} = await mermaid.render(`mermaid-${++diagramCounter}`, source);
	return svg;
}
