export async function copyText(text) {
	try {
		await navigator.clipboard.writeText(text);
	} catch {
		// Clipboard API is only available in secure contexts (https, localhost)
		const input = document.createElement("textarea");
		input.value = text;
		document.body.append(input);
		input.select();
		document.execCommand("copy");
		input.remove();
	}
}

let toastTimer = null;

export function toast(text) {
	const el = document.getElementById("toast");
	el.textContent = text;
	el.classList.add("visible");
	clearTimeout(toastTimer);
	toastTimer = setTimeout(() => el.classList.remove("visible"), 2500);
}

export function plural(n, one, few, many) {
	const mod10 = n % 10, mod100 = n % 100;
	if (mod10 === 1 && mod100 !== 11) return one;
	if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return few;
	return many;
}

const dateFormat = new Intl.DateTimeFormat("ru-RU", {dateStyle: "medium", timeStyle: "short"});

export const formatDate = value => dateFormat.format(new Date(value));
