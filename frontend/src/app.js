import {ChangeSet, Compartment, EditorState} from "@codemirror/state";
import {
	crosshairCursor,
	drawSelection,
	dropCursor,
	EditorView,
	highlightActiveLine,
	highlightActiveLineGutter,
	highlightSpecialChars,
	keymap,
	lineNumbers,
	rectangularSelection,
} from "@codemirror/view";
import {
	bracketMatching,
	defaultHighlightStyle,
	foldGutter,
	foldKeymap,
	indentOnInput,
	syntaxHighlighting,
} from "@codemirror/language";
import {defaultKeymap, history, historyKeymap, indentWithTab} from "@codemirror/commands";
import {highlightSelectionMatches, searchKeymap} from "@codemirror/search";
import {autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap} from "@codemirror/autocomplete";
import {collab, getClientID, getSyncedVersion, receiveUpdates, sendableUpdates} from "@codemirror/collab";
import {oneDark} from "@codemirror/theme-one-dark";
import {SYNTAXES, syntaxByCode, syntaxForFileName} from "./languages.js";
import {remoteCursors, setRemoteSelection, setRemoteUsers} from "./cursors.js";
import {renderMarkdown, setPreviewDark} from "./preview.js";
import {isDark, loadPrefs, onSystemThemeChange, savePrefs} from "./prefs.js";
import {setupHistoryDialog, setupProtectionDialog, setupSettingsDialog, setupShareDialog} from "./dialogs.js";
import {formatDate, plural, toast} from "./util.js";
import {setupStopwatch} from "./stopwatch.js";
import {setupMenu} from "./menu.js";

const $ = id => document.getElementById(id);

/** Editable id, or read-only id for viewers. */
const noteKey = document.body.dataset.key;
const readOnly = document.body.dataset.readOnly === "true";
const MAX_NOTE_LENGTH = 1_000_000;

const editorEl = $("editor");
const previewEl = $("preview");
const workspaceEl = $("workspace");
const syntaxSelect = $("syntax");
const previewButton = $("preview-toggle");

const prefs = loadPrefs();
const languageConf = new Compartment();
const themeConf = new Compartment();
const gutterConf = new Compartment();
const wrapConf = new Compartment();
const readOnlyConf = new Compartment();

let view = null;
let settings = {syntax: "plain", preview: false};
let protection = {hasPassword: false, expiresAt: null};
let readId = readOnly ? noteKey : null;
let myId = null;
/** Whether this browser created the note; the server enforces the same rights. */
let isOwner = false;
let users = [];
/** Set when the note is gone or inaccessible; stops reconnecting. */
let stopReason = null;

for (const syntax of SYNTAXES) {
	syntaxSelect.add(new Option(syntax.label, syntax.code));
}
if (readOnly) {
	document.body.classList.add("read-only");
	syntaxSelect.disabled = true;
	previewButton.disabled = true;
}

// ---- Synchronization (server side of the protocol lives in NoteRoom.kt) ----

let socket = null;
let epoch = null;
let pushing = false;
let reconnectDelay = 500;

/** Recently received changes by version: used to map cursors that other participants sent for older versions. */
const receivedChanges = [];
const MAX_RECEIVED_CHANGES = 500;

function connect() {
	const protocol = location.protocol === "https:" ? "wss:" : "ws:";
	let url = `${protocol}//${location.host}/ws/${readOnly ? "view" : "notes"}/${encodeURIComponent(noteKey)}`;
	if (view && epoch) {
		url += `?epoch=${encodeURIComponent(epoch)}&version=${getSyncedVersion(view.state)}`;
	}
	socket = new WebSocket(url);
	socket.onopen = () => {
		reconnectDelay = 500;
		sendProfile();
	};
	socket.onmessage = event => handleMessage(JSON.parse(event.data));
	socket.onclose = event => {
		socket = null;
		pushing = false;
		switch (event.code) {
			case 4401: // a password was set while we were offline
				location.reload();
				return;
			case 4404:
				stop("Заметка не найдена: возможно, она была удалена");
				return;
			case 4410:
				stop("Заметка удалена: истёк срок её жизни");
				return;
			case 4429:
				setStatus("offline", "Превышен лимит подключений, повтор позже…");
				reconnectDelay = Math.max(reconnectDelay, 15000);
				break;
			default:
				setStatus("offline", "Нет соединения, переподключение…");
		}
		if (stopReason) return;
		setTimeout(connect, reconnectDelay);
		reconnectDelay = Math.min(reconnectDelay * 2, 30000);
	};
}

function send(message) {
	if (socket?.readyState === WebSocket.OPEN) {
		socket.send(JSON.stringify(message));
	}
}

function handleMessage(message) {
	switch (message.type) {
		case "init":
			epoch = message.epoch;
			myId = message.you;
			isOwner = message.owner;
			document.body.classList.toggle("owner", isOwner);
			pushing = false;
			receivedChanges.length = 0;
			if (message.readId) readId = message.readId;
			applySettings(message);
			applyProtection(message);
			createEditor(message.doc, message.version);
			break;
		case "resume":
			pushing = false;
			receive(message.updates);
			scheduleCursor();
			break;
		case "updates":
			receive(message.updates);
			break;
		case "conflict":
			// The updates we were missing arrive before this message, so we are already rebased
			pushing = false;
			push();
			break;
		case "settings":
			applySettings(message);
			break;
		case "protection":
			applyProtection(message);
			break;
		case "presence":
			applyPresence(message.users);
			break;
		case "cursor":
			applyRemoteCursor(message);
			break;
		case "stopwatch":
			applyStopwatch(message);
			break;
		case "expired":
			stop("Заметка удалена: истёк срок её жизни");
			break;
		case "error":
			// Our state diverged from the server: start from scratch after reconnecting
			epoch = null;
			toast("Ошибка синхронизации, заметка будет перезагружена");
			break;
	}
}

function receive(updates) {
	if (!view) return;
	const base = getSyncedVersion(view.state);
	const parsed = updates.map(update => ({clientID: update.clientID, changes: ChangeSet.fromJSON(update.changes)}));
	parsed.forEach((update, i) => receivedChanges.push({version: base + i, changes: update.changes}));
	receivedChanges.splice(0, Math.max(0, receivedChanges.length - MAX_RECEIVED_CHANGES));

	const ownId = getClientID(view.state);
	view.dispatch(receiveUpdates(view.state, parsed));
	if (parsed.some(update => update.clientID === ownId)) {
		pushing = false;
		scheduleCursor();
	}
	push();
}

/** Sends local changes; at most one push is in flight, the rest are batched into the next one. */
function push() {
	if (!view || readOnly || pushing || socket?.readyState !== WebSocket.OPEN) return;
	const updates = sendableUpdates(view.state);
	if (!updates.length) return;
	pushing = true;
	send({
		type: "push",
		version: getSyncedVersion(view.state),
		updates: updates.map(update => ({clientID: update.clientID, changes: update.changes.toJSON()})),
	});
}

function stop(reason) {
	stopReason = reason;
	socket?.close();
	view?.dispatch({effects: readOnlyConf.reconfigure(EditorState.readOnly.of(true))});
	setStatus("offline", "Отключено");
	renderBanner();
}

setInterval(() => send({type: "ping"}), 25000);

// ---- Cursors of other participants ----

let cursorTimer = null;

function scheduleCursor() {
	if (cursorTimer) return;
	cursorTimer = setTimeout(() => {
		cursorTimer = null;
		sendCursor();
	}, 80);
}

function sendCursor() {
	// Positions must refer to a version everybody knows; unconfirmed changes are sent after the confirmation
	if (!view || sendableUpdates(view.state).length) return;
	send({
		type: "cursor",
		version: getSyncedVersion(view.state),
		ranges: view.state.selection.ranges.map(range => [range.anchor, range.head]),
	});
}

function applyRemoteCursor({id, version, ranges}) {
	if (!view) return;
	// The sender's version is never ahead of ours: the server sends updates and cursors in one order
	let positions = ranges.flat();
	const mapThrough = changes => positions = positions.map(pos => changes.mapPos(Math.min(pos, changes.length)));
	receivedChanges.filter(entry => entry.version >= version).forEach(entry => mapThrough(entry.changes));
	sendableUpdates(view.state).forEach(update => mapThrough(update.changes));
	const length = view.state.doc.length;
	const mapped = [];
	for (let i = 0; i < positions.length; i += 2) {
		mapped.push({anchor: Math.min(positions[i], length), head: Math.min(positions[i + 1], length)});
	}
	view.dispatch({effects: setRemoteSelection.of({id, ranges: mapped})});
}

// ---- Presence ----

function applyPresence(list) {
	const known = new Set(users.map(user => user.id));
	users = list;
	view?.dispatch({effects: setRemoteUsers.of(users.filter(user => user.id !== myId))});
	// Newcomers have not seen our cursor yet
	if (users.some(user => !known.has(user.id))) scheduleCursor();

	const presenceEl = $("presence");
	presenceEl.replaceChildren(...users.slice(0, 6).map(user => {
		const avatar = document.createElement("span");
		avatar.className = user.id === myId ? "avatar self" : "avatar";
		avatar.style.background = user.color;
		avatar.textContent = user.name.trim().charAt(0).toUpperCase() || "?";
		avatar.title = user.name + (user.id === myId ? " (вы)" : "") +
			(user.owner ? " · создатель" : "") + (user.readOnly ? " · только чтение" : "");
		return avatar;
	}));
	if (users.length > 6) {
		const more = document.createElement("span");
		more.className = "avatar more";
		more.textContent = `+${users.length - 6}`;
		presenceEl.append(more);
	}
	setStatus("online", `В сети · ${users.length} ${plural(users.length, "участник", "участника", "участников")}`);
}

let profileTimer = null;

function sendProfile() {
	clearTimeout(profileTimer);
	profileTimer = setTimeout(() => send({type: "profile", name: prefs.name, color: prefs.color}), 300);
}

// ---- Editor ----

const baseTheme = EditorView.theme({
	"&": {height: "100%"},
	".cm-scroller": {fontFamily: "var(--font-mono)", fontSize: "var(--editor-font-size)", lineHeight: "1.55"},
	// Room for the name labels of remote cursors on the first line
	".cm-content": {paddingTop: "18px"},
});

const gutters = () => prefs.lineNumbers ? [lineNumbers(), foldGutter(), highlightActiveLineGutter()] : [];

/** Drop a file onto the editor to replace the note with its content. */
const fileDrop = EditorView.domEventHandlers({
	drop(event) {
		const file = event.dataTransfer?.files?.[0];
		if (!file) return false;
		event.preventDefault();
		if (isOwner && !stopReason) loadFile(file);
		return true;
	},
});

function createEditor(doc, version) {
	const state = EditorState.create({
		doc,
		extensions: [
			gutterConf.of(gutters()),
			highlightSpecialChars(),
			history(),
			drawSelection(),
			dropCursor(),
			EditorState.allowMultipleSelections.of(true),
			indentOnInput(),
			syntaxHighlighting(defaultHighlightStyle, {fallback: true}),
			bracketMatching(),
			closeBrackets(),
			autocompletion(),
			rectangularSelection(),
			crosshairCursor(),
			highlightActiveLine(),
			highlightSelectionMatches(),
			keymap.of([
				...closeBracketsKeymap,
				...defaultKeymap,
				...searchKeymap,
				...historyKeymap,
				...foldKeymap,
				...completionKeymap,
				indentWithTab,
			]),
			wrapConf.of(prefs.wrap ? EditorView.lineWrapping : []),
			baseTheme,
			themeConf.of(isDark(prefs) ? oneDark : []),
			languageConf.of(syntaxByCode(settings.syntax).support),
			readOnlyConf.of(EditorState.readOnly.of(readOnly || stopReason !== null)),
			collab({startVersion: version}),
			remoteCursors,
			fileDrop,
			EditorView.updateListener.of(update => {
				if (update.docChanged) {
					push();
					schedulePreview();
				}
				if (update.selectionSet) scheduleCursor();
			}),
		],
	});
	if (view) {
		view.setState(state);
	} else {
		view = new EditorView({state, parent: editorEl});
		if (!readOnly) view.focus();
	}
	view.dispatch({effects: setRemoteUsers.of(users.filter(user => user.id !== myId))});
	renderPreview();
}

// ---- Settings shared by all participants ----

function applySettings({syntax, preview}) {
	settings = {syntax, preview};
	syntaxSelect.value = syntax;
	previewButton.setAttribute("aria-checked", String(preview));
	workspaceEl.classList.toggle("split", preview);
	previewEl.parentElement.hidden = !preview;
	view?.dispatch({effects: languageConf.reconfigure(syntaxByCode(syntax).support)});
	renderPreview();
}

function changeSettings(next) {
	applySettings(next);
	send({type: "settings", ...next});
}

syntaxSelect.addEventListener("change", () => {
	changeSettings({syntax: syntaxSelect.value, preview: settings.preview && syntaxSelect.value === "markdown"});
});

previewButton.addEventListener("click", () => {
	// The preview renders Markdown, so turning it on switches the note to Markdown
	changeSettings({syntax: "markdown", preview: !settings.preview});
});

function applyProtection(message) {
	protection = {hasPassword: message.hasPassword, expiresAt: message.expiresAt};
	protectionDialog.update(protection);
	renderBanner();
}

function renderBanner() {
	const messages = [];
	if (stopReason) messages.push(stopReason);
	if (readOnly) messages.push("Режим только для чтения");
	if (protection.expiresAt && !stopReason) messages.push(`Заметка будет удалена ${formatDate(protection.expiresAt)}`);
	const banner = $("banner");
	banner.textContent = messages.join(" · ");
	banner.hidden = !messages.length;
	banner.classList.toggle("error", Boolean(stopReason));
}

// ---- Markdown preview ----

let previewTimer = null;

function schedulePreview() {
	if (!settings.preview) return;
	clearTimeout(previewTimer);
	previewTimer = setTimeout(renderPreview, 120);
}

function renderPreview() {
	if (!settings.preview || !view) return;
	renderMarkdown(previewEl, view.state.doc.toString(), renderPreview);
}

// ---- Personal preferences ----

function applyPrefs() {
	const dark = isDark(prefs);
	if (prefs.theme === "system") {
		delete document.documentElement.dataset.theme;
	} else {
		document.documentElement.dataset.theme = prefs.theme;
	}
	editorEl.style.setProperty("--editor-font-size", `${prefs.fontSize}px`);
	setPreviewDark(dark);
	view?.dispatch({
		effects: [
			themeConf.reconfigure(dark ? oneDark : []),
			wrapConf.reconfigure(prefs.wrap ? EditorView.lineWrapping : []),
			gutterConf.reconfigure(gutters()),
		],
	});
	renderPreview();
}

onSystemThemeChange(applyPrefs);

const openSettings = setupSettingsDialog({
	prefs,
	onChange(change) {
		savePrefs(prefs);
		applyPrefs();
		if ("name" in change || "color" in change) sendProfile();
	},
});

// ---- Links, files, dialogs ----

const links = () => ({
	edit: readOnly ? null : `${location.origin}/${noteKey}`,
	read: readId ? `${location.origin}/r/${readId}` : location.href,
});

const openShare = setupShareDialog({
	getLinks: links,
	// A read-only link of a note that was never edited would lead nowhere: make sure it is saved
	onOpen: () => readOnly || send({type: "touch"}),
});

const openHistory = setupHistoryDialog({
	noteKey,
	onRestore: versionId => send({type: "restore", versionId}),
});

const protectionDialog = setupProtectionDialog({send});

const applyStopwatch = setupStopwatch({send});

setupMenu($("note-menu-button"), $("note-menu"));

$("share").addEventListener("click", openShare);
$("history").addEventListener("click", openHistory);
$("protection").addEventListener("click", protectionDialog.open);
$("settings").addEventListener("click", openSettings);

$("download").addEventListener("click", () => {
	if (!view) return;
	const blob = new Blob([view.state.doc.toString()], {type: "text/plain;charset=utf-8"});
	const link = document.createElement("a");
	link.href = URL.createObjectURL(blob);
	link.download = `note-${noteKey}.${syntaxByCode(settings.syntax).ext}`;
	link.click();
	setTimeout(() => URL.revokeObjectURL(link.href), 1000);
});

const uploadInput = $("upload-input");
$("upload").addEventListener("click", () => uploadInput.click());
uploadInput.addEventListener("change", () => {
	const file = uploadInput.files[0];
	uploadInput.value = "";
	if (file) loadFile(file);
});

async function loadFile(file) {
	if (!view) return;
	if (file.size > MAX_NOTE_LENGTH * 4) return toast("Файл слишком большой");
	const text = await file.text();
	if (text.length > MAX_NOTE_LENGTH) return toast("Файл слишком большой");
	if (view.state.doc.length && !confirm(`Заменить содержимое заметки файлом «${file.name}»?`)) return;
	view.dispatch({changes: {from: 0, to: view.state.doc.length, insert: text}, userEvent: "input.file"});
	const syntax = syntaxForFileName(file.name);
	if (syntax && syntax !== settings.syntax) {
		changeSettings({syntax, preview: settings.preview && syntax === "markdown"});
	}
	toast(`Загружен файл «${file.name}»`);
}

function setStatus(state, text) {
	$("status").dataset.state = state;
	$("status-text").textContent = text;
}

applyPrefs();
renderBanner();
connect();
