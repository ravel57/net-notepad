/** Dropdown opened by [button]; closes after a choice, a click outside or Escape. */
export function setupMenu(button, list) {
	const items = () => [...list.querySelectorAll('[role^="menuitem"]:not(:disabled)')];

	function open() {
		list.hidden = false;
		button.setAttribute("aria-expanded", "true");
		items()[0]?.focus();
	}

	function close({restoreFocus = false} = {}) {
		if (list.hidden) return;
		list.hidden = true;
		button.setAttribute("aria-expanded", "false");
		if (restoreFocus) button.focus();
	}

	function moveFocus(step) {
		const all = items();
		const index = all.indexOf(document.activeElement);
		const next = index < 0 ? (step > 0 ? 0 : all.length - 1) : (index + step + all.length) % all.length;
		all[next]?.focus();
	}

	button.addEventListener("click", () => list.hidden ? open() : close());
	button.addEventListener("keydown", event => {
		if (event.key === "ArrowDown") {
			event.preventDefault();
			open();
		}
	});
	// Item handlers run first, then the menu closes; dialogs opened by an item take the focus themselves
	list.addEventListener("click", event => event.target.closest('[role^="menuitem"]') && close());
	list.addEventListener("keydown", event => {
		switch (event.key) {
			case "Escape":
				close({restoreFocus: true});
				break;
			case "ArrowDown":
				moveFocus(1);
				break;
			case "ArrowUp":
				moveFocus(-1);
				break;
			case "Tab":
				close();
				return;
			default:
				return;
		}
		event.preventDefault();
	});
	document.addEventListener("pointerdown", event => {
		if (!button.contains(event.target) && !list.contains(event.target)) close();
	});
}
