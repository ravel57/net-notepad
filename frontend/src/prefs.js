// Per-browser preferences; the same key is read by static/js/theme.js before the page renders
const KEY = "netnotepad.prefs";

export const COLORS = ["#e5484d", "#f76b15", "#e2a336", "#30a46c", "#12a594", "#0090ff", "#6e56cf", "#d6409f"];
const ANIMALS = ["Лиса", "Сова", "Енот", "Ёж", "Панда", "Выдра", "Рысь", "Кит", "Бобр", "Сокол", "Тигр", "Коала"];

const pick = list => list[Math.floor(Math.random() * list.length)];

export function loadPrefs() {
	let stored = {};
	try {
		stored = JSON.parse(localStorage.getItem(KEY) || "{}");
	} catch {
		// Storage may be unavailable (private mode, blocked cookies)
	}
	const prefs = {
		name: `${pick(ANIMALS)} ${Math.floor(Math.random() * 90) + 10}`,
		color: pick(COLORS),
		theme: "system",
		fontSize: 14,
		wrap: true,
		lineNumbers: true,
		...stored,
	};
	savePrefs(prefs);
	return prefs;
}

export function savePrefs(prefs) {
	try {
		localStorage.setItem(KEY, JSON.stringify(prefs));
	} catch {
	}
}

const darkQuery = matchMedia("(prefers-color-scheme: dark)");

export const isDark = prefs => prefs.theme === "dark" || (prefs.theme === "system" && darkQuery.matches);

export function onSystemThemeChange(listener) {
	darkQuery.addEventListener("change", listener);
}
