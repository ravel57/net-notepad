import {LanguageDescription, LanguageSupport, StreamLanguage} from "@codemirror/language";
import {java} from "@codemirror/lang-java";
import {javascript} from "@codemirror/lang-javascript";
import {python} from "@codemirror/lang-python";
import {sql} from "@codemirror/lang-sql";
import {json} from "@codemirror/lang-json";
import {yaml} from "@codemirror/lang-yaml";
import {markdown, markdownLanguage} from "@codemirror/lang-markdown";
import {kotlin} from "@codemirror/legacy-modes/mode/clike";

// Codes must match ru.ravel.netnotepad.note.Syntax
const CODE_SYNTAXES = [
	{code: "java", label: "Java", ext: "java", support: java()},
	{code: "kotlin", label: "Kotlin", ext: "kt", aliases: ["kts"], support: new LanguageSupport(StreamLanguage.define(kotlin))},
	{code: "javascript", label: "JavaScript", ext: "js", aliases: ["mjs", "cjs", "jsx", "ts", "tsx"], support: javascript({jsx: true, typescript: true})},
	{code: "python", label: "Python", ext: "py", support: python()},
	{code: "sql", label: "SQL", ext: "sql", support: sql()},
	{code: "json", label: "JSON", ext: "json", support: json()},
	{code: "yaml", label: "YAML", ext: "yaml", aliases: ["yml"], support: yaml()},
];

export const SYNTAXES = [
	{code: "plain", label: "Обычный текст", ext: "txt", aliases: ["text", "log"], support: []},
	...CODE_SYNTAXES,
	{
		code: "markdown", label: "Markdown", ext: "md", aliases: ["markdown"],
		// Highlights fenced code blocks such as ```java
		support: markdown({
			base: markdownLanguage,
			codeLanguages: CODE_SYNTAXES.map(syntax => LanguageDescription.of({
				name: syntax.code,
				alias: [syntax.ext, ...(syntax.aliases ?? [])],
				support: syntax.support,
			})),
		}),
	},
];

export const syntaxByCode = code => SYNTAXES.find(syntax => syntax.code === code) ?? SYNTAXES[0];

export function syntaxForFileName(name) {
	const ext = name.includes(".") ? name.split(".").pop().toLowerCase() : "";
	return SYNTAXES.find(syntax => syntax.ext === ext || syntax.aliases?.includes(ext))?.code ?? null;
}
