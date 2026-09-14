window.__ModuleLoader__.load({
	id: "@deepseek-ai/dsh-client-ui-deliverables",
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
		let _deepseek_ai_dsh_client_store = require("@deepseek-ai/dsh-client-store");
		let react_jsx_runtime = require("react/jsx-runtime");
		let react = require("react");
		//#region ../../core/session/src/surface.ts
		/** Runtime counterpart of the message-producing event union. */
		const SURFACE_EVENT_TYPES = new Set([
			"user/message",
			"assistant/message",
			"tool/result"
		]);
		/**
		* Narrow an event to a surface-eligible event carrying its required marker.
		* @param event - event to test.
		* @returns true when both the type and marker identify a surface event.
		*/
		function isSurfaceEvent(event) {
			if (!SURFACE_EVENT_TYPES.has(event.type)) return false;
			return event.surfaceOp !== void 0;
		}
		/**
		* Narrow an event to an append-origin surface event: one that entered the
		* surface at its own log position and was never itself a replacement copy.
		*
		* The model-visible surface deliberately shadows replaced ranges, so it is the
		* wrong source for a human transcript — a landed replacement would erase
		* conversation the user already saw. Append-origin events are that transcript's
		* durable source material; replacement copies stay model-only.
		* @param event - event to test.
		* @returns true when the event appended to the surface tail.
		*/
		function isAppendSurfaceEvent(event) {
			return isSurfaceEvent(event) && event.surfaceOp === "append";
		}
		//#endregion
		//#region lib/types/client/turn-deliverables.js
		/**
		* Turn-scoped produced-file Definition and readers. Client-only and
		* model-free: the vocabulary comes from successful first-party mutation
		* calls, never presentation data or the closing prose.
		*/
		/**
		* Extract the path from a supported first-party mutation call. Session
		* `tool/call` events are root calls; Code Dispatch children do not enter this
		* Definition independently.
		* @param name - wire tool name.
		* @param argsRaw - model-produced JSON arguments.
		* @returns the mutation path, or null when the call is not a supported mutation.
		*/
		function mutationPath(name, argsRaw) {
			let args;
			try {
				args = JSON.parse(argsRaw);
			} catch {
				return null;
			}
			if (!isRecord(args)) return null;
			switch (name) {
				case "write": return typeof args.content === "string" ? pathValue(args.file_path) : null;
				case "edit": return validEditArgs(args) ? pathValue(args.file_path) : null;
				case "str_replace_editor": return editorMutationPath(args);
				default: return null;
			}
		}
		/** Validate the fields that an `edit` execution requires. */
		function validEditArgs(args) {
			return typeof args.old_string === "string" && args.old_string.length > 0 && typeof args.new_string === "string" && args.old_string !== args.new_string && (args.replace_all === void 0 || typeof args.replace_all === "boolean");
		}
		/** Extract a path only from a complete mutating editor command. */
		function editorMutationPath(args) {
			const path = pathValue(args.path);
			if (path === null) return null;
			switch (args.command) {
				case "create": return typeof args.file_text === "string" ? path : null;
				case "str_replace": return typeof args.old_str === "string" && args.old_str.length > 0 && (args.new_str === void 0 || typeof args.new_str === "string") ? path : null;
				case "insert": return typeof args.insert_line === "number" && Number.isInteger(args.insert_line) && args.insert_line >= 0 && typeof args.new_str === "string" ? path : null;
				default: return null;
			}
		}
		/** A non-blank path preserves the exact spelling supplied to the tool. */
		function pathValue(value) {
			return typeof value === "string" && value.trim().length > 0 ? value : null;
		}
		/** Narrow parsed JSON to an argument object. */
		function isRecord(value) {
			return typeof value === "object" && value !== null && !Array.isArray(value);
		}
		/**
		* Files produced by one Turn data value.
		*
		* The source is the arguments of successful `write`, `edit`, and mutating
		* `str_replace_editor` calls, not the closing prose: a produced file must be
		* listed whether or not the model remembered to name it. Reads, unsupported
		* tools, malformed calls, and failed results contribute nothing. Paths keep
		* first-seen order and appear once, so a file written and then edited in the
		* same turn is one entry.
		*
		* The Conversation Location index owns turn membership before this function
		* runs, so paths cannot spill across turns and this derivation does not infer
		* boundaries from neighboring presentation Nodes.
		* @param data - engine-published Deliverables data for one Turn.
		* @param seq - closing Assistant seq; later Tool settlements are excluded.
		* @returns Produced paths in first-seen order; empty when the turn wrote nothing.
		*/
		function producedForClosing(data, seq = Number.POSITIVE_INFINITY) {
			if (data === void 0) return [];
			const paths = [];
			const seen = /* @__PURE__ */ new Set();
			for (const produced of data.produced) {
				if (produced.seq > seq || seen.has(produced.path)) continue;
				seen.add(produced.path);
				paths.push(produced.path);
			}
			return paths;
		}
		/**
		* Claim the turn-tail chain only when its closing turn produced files.
		* @param owner - Turn-tail owner currency for the closing assistant.
		* @returns Produced paths as the component's match, or null to decline before mount.
		*/
		function selectProducedFiles(owner) {
			const paths = producedForClosing(owner.turn.data.get("deliverables"), owner.seq);
			return paths.length === 0 ? null : paths;
		}
		/** Turn-local successful mutation accumulator; it publishes no view Node. */
		const deliverablesDefinition = {
			kind: "deliverables",
			match: (event) => {
				if (event.type === "turn/start") return {
					id: String(event.data.turn),
					role: "start"
				};
				if (event.type === "tool/call") return {
					id: String(event.data.turn),
					role: "update"
				};
				if (event.type === "tool/result" && isAppendSurfaceEvent(event)) return {
					id: String(event.data.turn),
					role: "update"
				};
				return null;
			},
			start: (_context, match) => {
				if (match.event.type !== "turn/start") throw new Error("deliverables start requires turn/start");
				return {
					turn: match.event.data.turn,
					calls: /* @__PURE__ */ new Map(),
					produced: []
				};
			},
			update: (context, match) => {
				if (match.event.type === "tool/call") {
					const calls = new Map(context.state.calls);
					calls.set(String(match.event.data.callId), mutationPath(match.event.data.name, match.event.data.arguments));
					return {
						...context.state,
						calls
					};
				}
				if (match.event.type !== "tool/result") return context.state;
				if (match.event.data.message.content[0].isError === true) return context.state;
				const callId = String(match.event.data.message.source.callId);
				const path = context.state.calls.get(callId);
				return path === null || path === void 0 ? context.state : {
					...context.state,
					produced: [...context.state.produced, {
						seq: match.event.seq,
						path
					}]
				};
			},
			buildLocationData: (context, scope, previous) => {
				if (scope !== "turn" || context.state === void 0) return null;
				if (previous?.kind === "turn" && previous.turn === context.state.turn && previous.key === "deliverables" && previous.value.produced === context.state.produced) return previous;
				return {
					kind: "turn",
					turn: context.state.turn,
					key: "deliverables",
					value: { produced: context.state.produced }
				};
			}
		};
		/**
		* Trailing path segment, the part that identifies the file at a glance.
		* @param path - Slash- or backslash-separated path.
		* @returns The final segment, or the whole string when separator-free.
		*/
		function basename(path) {
			const at = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
			return at === -1 ? path : path.slice(at + 1);
		}
		/**
		* File-mention vocabulary over one turn's produced paths, for the closing
		* message's prose: an inline-code token opens the file it names. A token
		* resolves by exact path, or by being exactly the basename of exactly one
		* produced path — a basename two paths share stays inert rather than
		* guessing, so a mention link can never open the wrong file or 404.
		* @param paths - The turn's produced paths (tool order, already deduped).
		* @param openFile - The chat view's file opener.
		* @param label - Localizes the accessible open-label for a resolved path.
		* @returns The resolver MarkdownText consumes; the full path rides `title`,
		* the same disambiguator the row's chips carry.
		*/
		function producedFileMentions(paths, openFile, label) {
			return { resolve(value) {
				const path = paths.includes(value) ? value : onlyPathWithBasename(paths, value);
				if (path === void 0) return void 0;
				return {
					open: () => {
						openFile(path);
					},
					label: label(path),
					title: path
				};
			} };
		}
		/** The single produced path whose basename is exactly `value`, else undefined. */
		function onlyPathWithBasename(paths, value) {
			const matches = paths.filter((path) => basename(path) === value);
			return matches.length === 1 ? matches[0] : void 0;
		}
		//#endregion
		//#region \0dsh-css:/home/runner/work/deepseek-harness/deepseek-harness/packages/client/ui-deliverables/src/client/ProducedFiles.module.css.mjs
		const css = ".P4kPIW_root{grid-template-columns:max-content minmax(0,1fr);align-items:start;column-gap:8px;margin-top:16px;font-size:13px;line-height:22px;display:grid}.P4kPIW_label{color:var(--dsw-alias-label-tertiary);grid-area:1/1}.P4kPIW_lane{--produced-file-chip-max:96px;--produced-file-gap:8px;grid-area:1/2;row-gap:6px;min-width:0;display:grid;container-type:inline-size}.P4kPIW_row{align-items:center;gap:var(--produced-file-gap);flex-wrap:nowrap;min-width:0;display:flex;overflow:hidden}.P4kPIW_file{box-sizing:border-box;min-width:0;max-width:var(--produced-file-chip-max);text-overflow:ellipsis;white-space:nowrap;background:var(--dsw-alias-interactive-bg-hover);color:var(--dsw-alias-label-secondary);font:inherit;cursor:pointer;border:none;border-radius:6px;flex:0 auto;margin:0;padding:0 8px;overflow:hidden}.P4kPIW_file:hover{color:var(--dsw-alias-label-primary);text-decoration:underline}.P4kPIW_file:focus-visible,.P4kPIW_showFolder:focus-visible{box-shadow:inset 0 0 0 2px var(--dsw-alias-border-l3);outline:none}.P4kPIW_more{white-space:nowrap;color:var(--dsw-alias-label-tertiary);flex:none;display:none}.P4kPIW_more[data-shown=\"6\"]{display:inline}.P4kPIW_showFolder{display:none}.P4kPIW_lane:has(.P4kPIW_more[data-shown=\"6\"])>.P4kPIW_showFolder{display:block}@container (width<=687px){.P4kPIW_file:nth-of-type(6),.P4kPIW_more[data-shown=\"6\"]{display:none}.P4kPIW_more[data-shown=\"5\"]{display:inline}.P4kPIW_showFolder{display:none}.P4kPIW_lane:has(.P4kPIW_more[data-shown=\"5\"])>.P4kPIW_showFolder{display:block}}@container (width<=583px){.P4kPIW_file:nth-of-type(5),.P4kPIW_more[data-shown=\"5\"]{display:none}.P4kPIW_more[data-shown=\"4\"]{display:inline}.P4kPIW_showFolder{display:none}.P4kPIW_lane:has(.P4kPIW_more[data-shown=\"4\"])>.P4kPIW_showFolder{display:block}}@container (width<=479px){.P4kPIW_file:nth-of-type(4),.P4kPIW_more[data-shown=\"4\"]{display:none}.P4kPIW_more[data-shown=\"3\"]{display:inline}.P4kPIW_showFolder{display:none}.P4kPIW_lane:has(.P4kPIW_more[data-shown=\"3\"])>.P4kPIW_showFolder{display:block}}@container (width<=375px){.P4kPIW_file:nth-of-type(3),.P4kPIW_more[data-shown=\"3\"]{display:none}.P4kPIW_more[data-shown=\"2\"]{display:inline}.P4kPIW_showFolder{display:none}.P4kPIW_lane:has(.P4kPIW_more[data-shown=\"2\"])>.P4kPIW_showFolder{display:block}}@container (width<=271px){.P4kPIW_file:nth-of-type(2),.P4kPIW_more[data-shown=\"2\"]{display:none}.P4kPIW_more[data-shown=\"1\"]{display:inline}.P4kPIW_showFolder{display:none}.P4kPIW_lane:has(.P4kPIW_more[data-shown=\"1\"])>.P4kPIW_showFolder{display:block}}.P4kPIW_showFolder{color:var(--dsw-alias-label-tertiary);font:inherit;cursor:pointer;background:0 0;border:none;border-radius:4px;justify-self:start;margin:0;padding:0 2px;line-height:20px}.P4kPIW_showFolder:hover{color:var(--dsw-alias-label-secondary);text-decoration:underline}";
		const tagId = "@deepseek-ai/dsh-client-ui-deliverables/ProducedFiles.module.css";
		if (typeof document !== "undefined" && document.querySelector("style[data-plugin-css=" + JSON.stringify(tagId) + "]") === null) {
			const tag = document.createElement("style");
			tag.dataset.plugin = "@deepseek-ai/dsh-client-ui-deliverables";
			tag.dataset.pluginCss = tagId;
			tag.textContent = css;
			document.head.appendChild(tag);
		}
		var ProducedFiles_module_css_default = {
			"file": "P4kPIW_file",
			"label": "P4kPIW_label",
			"lane": "P4kPIW_lane",
			"more": "P4kPIW_more",
			"root": "P4kPIW_root",
			"row": "P4kPIW_row",
			"showFolder": "P4kPIW_showFolder"
		};
		//#endregion
		//#region lib/types/client/ProducedFiles.js
		/** Maximum number of file chips rendered before the remainder counter. */
		const SHOWN_LIMIT = 6;
		function moreLabel(t, count) {
			return count === 1 ? t("produced.moreOne") : t("produced.more", { count: String(count) });
		}
		/**
		* Render one turn's produced files as openable chips.
		* @param props - selector-matched paths, the chat view's file opener, and the locale seat.
		* @returns The produced-files row.
		*/
		function ProducedFiles({ matched: paths, openFile, isLoopback, ensureWorkspacePathOpen, useWorkspacePathOpen, t }) {
			(0, react.useEffect)(() => {
				ensureWorkspacePathOpen();
			}, [ensureWorkspacePathOpen]);
			const hostCanOpenPath = useWorkspacePathOpen((available) => available === true);
			const canOpenPath = isLoopback && hostCanOpenPath;
			const shown = paths.slice(0, SHOWN_LIMIT);
			return (0, react_jsx_runtime.jsxs)("div", {
				className: ProducedFiles_module_css_default.root,
				children: [(0, react_jsx_runtime.jsx)("span", {
					className: ProducedFiles_module_css_default.label,
					children: t("produced.label")
				}), (0, react_jsx_runtime.jsxs)("div", {
					className: ProducedFiles_module_css_default.lane,
					children: [(0, react_jsx_runtime.jsxs)("div", {
						className: ProducedFiles_module_css_default.row,
						"data-produced-files-row": true,
						children: [shown.map((path) => (0, react_jsx_runtime.jsx)("button", {
							type: "button",
							className: ProducedFiles_module_css_default.file,
							title: path,
							"aria-label": t("produced.open", { name: path }),
							onClick: () => {
								openFile(path);
							},
							children: basename(path)
						}, path)), shown.map((_, index) => {
							const shownCount = index + 1;
							const remainder = paths.length - shownCount;
							if (remainder <= 0) return null;
							return (0, react_jsx_runtime.jsx)("span", {
								className: ProducedFiles_module_css_default.more,
								"data-shown": shownCount,
								children: moreLabel(t, remainder)
							}, shownCount);
						})]
					}), paths.length > 1 && canOpenPath && (0, react_jsx_runtime.jsx)("button", {
						type: "button",
						className: ProducedFiles_module_css_default.showFolder,
						onClick: () => {
							openFile(".");
						},
						children: t("produced.showInFolder")
					})]
				})]
			});
		}
		//#endregion
		//#region lib/types/client/locales.js
		/** `deliverables` namespace dictionaries. */
		/** Dictionary namespace owned by this plugin. */
		const NS = "deliverables";
		/** Simplified Chinese dictionary (the key-set source of truth). */
		const zh = {
			"produced.label": "产物",
			"produced.moreOne": "+ 1 个文件",
			"produced.more": "+ {count} 个文件",
			"produced.open": "打开 {name}",
			"produced.showInFolder": "在文件夹中显示"
		};
		/** English dictionary (same key set). */
		const en = {
			"produced.label": "Produced",
			"produced.moreOne": "+ 1 file",
			"produced.more": "+ {count} files",
			"produced.open": "Open {name}",
			"produced.showInFolder": "Show in folder"
		};
		//#endregion
		//#region lib/types/client/index.js
		/** Required services for the tail-slot registration and its dictionaries. */
		const inject = [
			"slots",
			"locale",
			"uiConversation",
			"remote",
			"remote.session"
		];
		/**
		* Client plugin body: register the dictionaries and the turn-tail entry.
		* @param ctx - client root context.
		*/
		function apply(ctx) {
			const workspacePathOpen = (0, _deepseek_ai_dsh_client_store.createSnapshotStore)(void 0);
			let requestedWorkspacePathOpen = false;
			let capabilityRevision = 0;
			let pendingCapability;
			const loadWorkspacePathOpen = () => {
				if (pendingCapability !== void 0) return;
				const revision = capabilityRevision;
				const pending = ctx.remote.session.canOpenWorkspacePath().then((result) => {
					if (revision === capabilityRevision) workspacePathOpen.set(result.ok && result.value);
				}).finally(() => {
					if (pendingCapability === pending) pendingCapability = void 0;
				});
				pendingCapability = pending;
			};
			const ensureWorkspacePathOpen = () => {
				requestedWorkspacePathOpen = true;
				if (workspacePathOpen.getSnapshot() === void 0) loadWorkspacePathOpen();
			};
			ctx.on("connection/reset", () => {
				capabilityRevision++;
				pendingCapability = void 0;
				workspacePathOpen.set(void 0);
				if (requestedWorkspacePathOpen) loadWorkspacePathOpen();
			});
			ctx.uiConversation.events.register(deliverablesDefinition);
			ctx.effect(() => ctx.locale.register(NS, {
				zh,
				en
			}), "ui-deliverables: dictionaries");
			ctx.slots.inject("conversation.chat.turnTail", () => ctx.slots.register({
				name: "conversation.chat.turnTail",
				select: selectProducedFiles,
				locale: NS,
				inject: () => ({
					isLoopback: ctx.remote.$host.isLoopback,
					ensureWorkspacePathOpen,
					hooks: { workspacePathOpen }
				})
			}, ProducedFiles));
			const t = ctx.locale.bind(NS);
			ctx.provide("chatFileMentions", { forClosing(owner) {
				const paths = selectProducedFiles(owner);
				if (paths === null) return void 0;
				return producedFileMentions(paths, owner.openFile, (path) => t("produced.open", { name: path }));
			} });
		}
		//#endregion
		exports.ProducedFiles = ProducedFiles;
		exports.apply = apply;
		exports.inject = inject;
		exports.producedForClosing = producedForClosing;
		return module.exports;
	}
});

//# sourceMappingURL=client.js.map