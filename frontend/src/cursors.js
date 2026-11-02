import {StateEffect, StateField} from "@codemirror/state";
import {Decoration, EditorView, WidgetType} from "@codemirror/view";

/** Replaces the list of other participants: `[{id, name, color}]`. */
export const setRemoteUsers = StateEffect.define();

/** Sets the selection of a participant: `{id, ranges: [{anchor, head}]}` in current document positions. */
export const setRemoteSelection = StateEffect.define();

/** How long the name stays visible after the participant moved the cursor. */
const LABEL_VISIBLE_MS = 2500;

class CursorWidget extends WidgetType {
	/** [moves] changes whenever the participant moves the cursor, so the DOM and its fade-out animation restart. */
	constructor(name, color, moves) {
		super();
		this.name = name;
		this.color = color;
		this.moves = moves;
	}

	eq(other) {
		return other.name === this.name && other.color === this.color && other.moves === this.moves;
	}

	toDOM() {
		const cursor = document.createElement("span");
		cursor.className = "cm-remote-cursor";
		cursor.style.borderColor = this.color;
		const label = document.createElement("span");
		label.className = "cm-remote-cursor-label";
		label.style.background = this.color;
		label.textContent = this.name;
		cursor.append(label);
		return cursor;
	}

	ignoreEvent() {
		// Let clicks next to someone else's cursor place our own one
		return false;
	}
}

const remoteState = StateField.define({
	create: () => ({users: new Map(), selections: new Map()}),
	update(value, tr) {
		let {users, selections} = value;
		if (tr.docChanged) {
			// Keep other people's cursors in place while the text around them changes
			selections = new Map([...selections].map(([id, selection]) => [id, {
				...selection,
				ranges: selection.ranges.map(range => ({
					anchor: tr.changes.mapPos(range.anchor),
					head: tr.changes.mapPos(range.head),
				})),
			}]));
		}
		for (const effect of tr.effects) {
			if (effect.is(setRemoteUsers)) {
				users = new Map(effect.value.map(user => [user.id, user]));
				selections = new Map([...selections].filter(([id]) => users.has(id)));
			} else if (effect.is(setRemoteSelection)) {
				const moves = (selections.get(effect.value.id)?.moves ?? 0) + 1;
				selections = new Map(selections).set(effect.value.id, {ranges: effect.value.ranges, moves});
			}
		}
		return users === value.users && selections === value.selections ? value : {users, selections};
	},
	provide: field => EditorView.decorations.compute([field], state => {
		const {users, selections} = state.field(field);
		const length = state.doc.length;
		const decorations = [];
		for (const [id, {ranges, moves}] of selections) {
			const user = users.get(id);
			if (!user) continue;
			for (const range of ranges) {
				const anchor = Math.min(range.anchor, length), head = Math.min(range.head, length);
				if (anchor !== head) {
					decorations.push(Decoration.mark({
						class: "cm-remote-selection",
						attributes: {style: `background-color: ${user.color}33`},
					}).range(Math.min(anchor, head), Math.max(anchor, head)));
				}
				decorations.push(Decoration.widget({widget: new CursorWidget(user.name, user.color, moves), side: 1}).range(head));
			}
		}
		return Decoration.set(decorations, true);
	}),
});

export const remoteCursors = [
	remoteState,
	EditorView.baseTheme({
		".cm-remote-cursor": {
			position: "relative",
			borderLeft: "2px solid",
			marginLeft: "-1px",
			marginRight: "-1px",
		},
		// A wider invisible area makes the thin cursor easy to hover
		".cm-remote-cursor::before": {
			content: '""',
			position: "absolute",
			top: "0",
			bottom: "0",
			left: "-4px",
			right: "-4px",
		},
		// The name covers the line above, so it is shown only briefly after a move and on hover
		"@keyframes cm-remote-cursor-label-fade": {
			"0%, 80%": {opacity: "1"},
			"100%": {opacity: "0"},
		},
		".cm-remote-cursor-label": {
			position: "absolute",
			bottom: "100%",
			left: "-2px",
			padding: "0 4px",
			borderRadius: "3px 3px 3px 0",
			color: "#fff",
			font: "11px/1.5 var(--font-sans)",
			whiteSpace: "nowrap",
			pointerEvents: "none",
			zIndex: "10",
			opacity: "0",
			animation: `cm-remote-cursor-label-fade ${LABEL_VISIBLE_MS}ms ease-in`,
			transition: "opacity .15s",
		},
		".cm-remote-cursor:hover .cm-remote-cursor-label": {
			animation: "none",
			opacity: "1",
		},
	}),
];
