// Stopwatch shared by everyone in the note; the server keeps its state and broadcasts every change
const TICK_MS = 250;

const $ = id => document.getElementById(id);

const pad = n => String(n).padStart(2, "0");

/** 0:05, 12:40, 1:02:07 */
function format(ms) {
	const seconds = Math.floor(ms / 1000) % 60;
	const minutes = Math.floor(ms / 60_000) % 60;
	const hours = Math.floor(ms / 3_600_000);
	return hours ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

/** Returns a function that applies the state received from the server: `{running, elapsed}`. */
export function setupStopwatch({send}) {
	const timeEl = $("stopwatch-time");
	const toggleButton = $("stopwatch-toggle");
	const resetButton = $("stopwatch-reset");
	let running = false;
	/** Elapsed milliseconds at [syncedAt]; counting from it does not depend on the server clock. */
	let elapsed = 0;
	let syncedAt = performance.now();
	let timer = null;

	const current = () => elapsed + (running ? performance.now() - syncedAt : 0);

	function apply(state) {
		running = Boolean(state.running);
		elapsed = Math.max(0, Number(state.elapsed) || 0);
		syncedAt = performance.now();
		render();
	}

	function render() {
		const value = current();
		timeEl.textContent = format(value);
		const action = running ? "Пауза" : value ? "Продолжить" : "Запустить";
		toggleButton.title = action;
		toggleButton.setAttribute("aria-label", action);
		toggleButton.setAttribute("aria-pressed", String(running));
		resetButton.hidden = !running && !value;
		if (running && !timer) {
			timer = setInterval(() => timeEl.textContent = format(current()), TICK_MS);
		} else if (!running && timer) {
			clearInterval(timer);
			timer = null;
		}
	}

	// The change is shown right away; the server's broadcast then puts everyone on the same state
	toggleButton.addEventListener("click", () => {
		send({type: "stopwatch", action: running ? "pause" : "start"});
		apply({running: !running, elapsed: current()});
	});
	resetButton.addEventListener("click", () => {
		send({type: "stopwatch", action: "reset"});
		apply({running: false, elapsed: 0});
	});

	render();
	return apply;
}
