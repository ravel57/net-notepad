// Applies the theme chosen in the settings before the page renders (see frontend/src/prefs.js)
try {
	const theme = JSON.parse(localStorage.getItem("netnotepad.prefs") || "{}").theme;
	if (theme === "light" || theme === "dark") document.documentElement.dataset.theme = theme;
} catch {
}
