import {copyText, formatDate, plural, toast} from "./util.js";
import {COLORS} from "./prefs.js";
import {syntaxByCode} from "./languages.js";

const $ = id => document.getElementById(id);

// Close buttons and clicks on the backdrop
for (const dialog of document.querySelectorAll("dialog")) {
	dialog.addEventListener("click", event => event.target === dialog && dialog.close());
	dialog.querySelectorAll("[data-close]").forEach(button => button.addEventListener("click", () => dialog.close()));
}

/** Links for editing and reading plus a QR code of the selected one. */
export function setupShareDialog({getLinks, onOpen}) {
	const dialog = $("share-dialog");
	let qrTarget = null;

	async function renderQr() {
		const links = getLinks();
		const url = links[qrTarget] ?? links.read;
		document.querySelectorAll("#qr-switch [data-qr]").forEach(button =>
			button.setAttribute("aria-pressed", String(button.dataset.qr === qrTarget)));
		const {default: QRCode} = await import("qrcode");
		$("qr").innerHTML = await QRCode.toString(url, {type: "svg", margin: 1, errorCorrectionLevel: "M"});
	}

	document.querySelectorAll("#qr-switch [data-qr]").forEach(button => button.addEventListener("click", () => {
		qrTarget = button.dataset.qr;
		renderQr();
	}));
	dialog.querySelectorAll("[data-copy]").forEach(button => button.addEventListener("click", async () => {
		await copyText($(button.dataset.copy).value);
		toast("Ссылка скопирована");
	}));

	return () => {
		onOpen();
		const links = getLinks();
		$("share-edit-link").value = links.edit ?? "";
		$("share-read-link").value = links.read ?? "";
		qrTarget ??= links.edit ? "edit" : "read";
		dialog.showModal();
		renderQr();
	};
}

/** Automatic snapshots of the note and restoring one of them. */
export function setupHistoryDialog({noteKey, onRestore}) {
	const dialog = $("history-dialog");
	const list = $("history-list");
	const content = $("history-content");
	const restoreButton = $("history-restore");
	let selected = null;

	async function fetchJson(url) {
		const response = await fetch(url, {headers: {Accept: "application/json"}});
		if (!response.ok) throw new Error(`HTTP ${response.status}`);
		return response.json();
	}

	async function select(version, item) {
		selected = version;
		list.querySelectorAll(".selected").forEach(el => el.classList.remove("selected"));
		item.classList.add("selected");
		restoreButton.disabled = true;
		content.textContent = "Загрузка…";
		try {
			const full = await fetchJson(`/api/notes/${encodeURIComponent(noteKey)}/versions/${version.id}`);
			if (selected !== version) return;
			content.textContent = full.content;
			restoreButton.disabled = false;
		} catch (error) {
			content.textContent = `Не удалось загрузить версию: ${error.message}`;
		}
	}

	restoreButton.addEventListener("click", () => {
		if (!selected) return;
		onRestore(selected.id);
		dialog.close();
		toast("Версия восстановлена");
	});

	return async () => {
		selected = null;
		restoreButton.disabled = true;
		content.textContent = "Выберите версию слева";
		list.replaceChildren();
		dialog.showModal();
		try {
			const versions = await fetchJson(`/api/notes/${encodeURIComponent(noteKey)}/versions`);
			if (!versions.length) {
				list.innerHTML = `<li class="muted">Сохранённых версий пока нет</li>`;
				return;
			}
			for (const version of versions) {
				const item = document.createElement("li");
				const button = document.createElement("button");
				button.type = "button";
				button.innerHTML = `<span></span><small class="muted"></small>`;
				button.firstChild.textContent = formatDate(version.createdAt);
				button.lastChild.textContent =
					`${version.length} ${plural(version.length, "символ", "символа", "символов")} · ${syntaxByCode(version.syntax).label}`;
				button.addEventListener("click", () => select(version, item));
				item.append(button);
				list.append(item);
			}
		} catch (error) {
			list.innerHTML = `<li class="error"></li>`;
			list.firstChild.textContent = `Не удалось загрузить историю: ${error.message}`;
		}
	};
}

/** Password and lifetime of the note; changes are applied on the server and broadcast to everyone. */
export function setupProtectionDialog({send}) {
	const dialog = $("protection-dialog");
	const passwordInput = $("password-input");

	function setPassword() {
		const password = passwordInput.value;
		if (!password) return passwordInput.focus();
		send({type: "protect", password});
		passwordInput.value = "";
		toast("Пароль установлен");
	}

	$("password-set").addEventListener("click", setPassword);
	passwordInput.addEventListener("keydown", event => {
		if (event.key === "Enter") {
			event.preventDefault();
			setPassword();
		}
	});
	$("password-remove").addEventListener("click", () => {
		send({type: "protect", password: ""});
		toast("Пароль снят");
	});
	$("ttl-apply").addEventListener("click", () => {
		send({type: "protect", ttl: Number($("ttl-select").value)});
		toast("Срок жизни обновлён");
	});

	return {
		open: () => dialog.showModal(),
		update({hasPassword, expiresAt}) {
			$("password-state").textContent = hasPassword ? "Пароль установлен" : "Пароль не установлен";
			$("password-remove").hidden = !hasPassword;
			$("ttl-state").textContent = expiresAt ? `Заметка будет удалена ${formatDate(expiresAt)}` : "Без ограничения";
		},
	};
}

/** Per-browser preferences: name and cursor color, theme, font size, wrapping, line numbers. */
export function setupSettingsDialog({prefs, onChange}) {
	const dialog = $("settings-dialog");
	const swatches = $("pref-colors");

	for (const color of COLORS) {
		const swatch = document.createElement("button");
		swatch.type = "button";
		swatch.className = "swatch";
		swatch.style.background = color;
		swatch.title = color;
		swatch.addEventListener("click", () => update({color}));
		swatches.append(swatch);
	}

	function render() {
		$("pref-name").value = prefs.name;
		$("pref-theme").value = prefs.theme;
		$("pref-font-size").value = prefs.fontSize;
		$("pref-font-size-value").textContent = `${prefs.fontSize} px`;
		$("pref-wrap").checked = prefs.wrap;
		$("pref-line-numbers").checked = prefs.lineNumbers;
		swatches.querySelectorAll(".swatch").forEach(swatch =>
			swatch.setAttribute("aria-pressed", String(swatch.title === prefs.color)));
	}

	function update(change) {
		Object.assign(prefs, change);
		render();
		onChange(change);
	}

	$("pref-name").addEventListener("input", event => update({name: event.target.value.slice(0, 40)}));
	$("pref-theme").addEventListener("change", event => update({theme: event.target.value}));
	$("pref-font-size").addEventListener("input", event => update({fontSize: Number(event.target.value)}));
	$("pref-wrap").addEventListener("change", event => update({wrap: event.target.checked}));
	$("pref-line-numbers").addEventListener("change", event => update({lineNumbers: event.target.checked}));

	return () => {
		render();
		dialog.showModal();
	};
}
