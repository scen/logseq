# File System Watching for Logseq Web (MD / file graph)

## Context

The user wants to implement file system watching on the **Logseq web app, MD (file-graph)
version** — not the DB version, not Electron. They use Chrome only, so we can use the
Chrome 129+ `FileSystemObserver` API.

Logseq's web app historically used the browser File System Access API (NFS) to back the
MD/file-graph version, but it had **no file watching** — `watch-dir!` / `unwatch-dir!` in
`frontend.fs.nfs` are stubs that return `nil`, so external edits (e.g., from a text editor)
are never picked up until the user refreshes. The Electron build gets watcher events from
chokidar via IPC; the mobile build uses platform watchers; the web build was always
refresh-only.

Filling in the two protocol stubs with a `FileSystemObserver`-backed implementation will
flow real change events into the existing `frontend.fs.watcher-handler/handle-changed!`
pipeline — the same pipeline the Electron watcher feeds. No other code needs to move.

## IMPORTANT: base commit

Current `master` **does not have** web NFS support. It was removed by commit
`de88511bd` ("chore: remove nfs support", 2025-05-13) on the feat/db branch, and the
current master has no `src/main/frontend/fs/nfs.cljs`, no `openDirectory` run-path for
opening a graph, etc. All work must be done on a branch cut from an older commit.

**Target base commit: `4a0225b7e`** (2025-05-12, "fix: query function for db graphs" by
Gabriel Horner). This is the last master-line commit where `src/main/frontend/fs/nfs.cljs`
still exists. Its immediate child on the feat/db branch (`de88511bd`) is what deleted NFS.

### How to start

The user has two checkouts:
- Primary: `/Users/scen/Dropbox/code/logseq` — keep on `master`, don't touch.
- Secondary: `/Users/scen/code/logseq` — use this for the work.

**Do NOT run `git checkout`** on the user's behalf without explicit confirmation — an 8+
-month-old commit touches thousands of files. Confirm location and ask before branching.
Suggested command the user or you (after confirmation) will run:

```
cd /Users/scen/code/logseq && git checkout -b feat/fs-observer 4a0225b7e
```

Note: `4a0225b7e` predates the repo's migration from yarn to pnpm (`79c25837c`,
"Migrate from yarn to pnpm"). At `4a0225b7e` the repo uses **yarn**, not pnpm. Package
installs and dev scripts use yarn at that commit.

## Scope

- Web (NFS) only. Electron / Capacitor / memory-fs backends untouched.
- MD/file graph only. DB graphs don't go through `watcher-handler/handle-changed!`.
- Observe files with the existing NFS filter (`md`, `org`, `excalidraw`, `edn`, `css`;
  skip dotfiles, `logseq/bak/`, `logseq/version-files/`).
- Chrome-only. Feature-detect `FileSystemObserver`; if absent, keep the current no-watch
  behavior and show one notification.

## Change policy (what happens when disk changes)

Observer events flow into the existing `watcher-handler/handle-changed!` pipeline
(`src/main/frontend/fs/watcher_handler.cljs`). The inherited behavior — same as the
Electron watcher today — is:

1. **No-op when equal.** If disk content equals DB content (after `string/trim`), drop
   the event. This is what suppresses self-write feedback from `nfs/write-file!`.
2. **Auto-accept with `logseq/bak/` backup.** For `add`/`change` where content differs,
   `handle-add-and-change!` calls `file-handler/backup-file!` to save the previous DB
   content to a versioned file under `logseq/bak/`, then `file-handler/alter-file` with
   `:from-disk? true :fs/event :fs/local-file-change` replaces the file and re-renders.
   **No modal, no per-change prompt.** This is what the user selected.
3. **Conflict prompts live on the write side, not the watch side.** `nfs/write-file!` is
   where `[:file/not-matched-from-disk ...]` is emitted — that's unchanged by this work.

## Files to modify

### 1. `src/main/frontend/utils.js` (add JS exports)

Thin wrappers over the `FileSystemObserver` API. No filtering/parsing here — keep that
in cljs. Add near the existing `openDirectory` / `verifyPermission` exports.

- `fileSystemObserverSupported()` → `typeof window.FileSystemObserver === 'function'`.
- `createFsObserver(rootHandle, jsCallback)`:
  - `const observer = new FileSystemObserver(jsCallback)`
  - `await observer.observe(rootHandle, { recursive: true })`
  - return `observer` so cljs can call `.disconnect()` (and/or `.unobserve(rootHandle)`).
- The callback Logseq passes will receive the raw `(records, observer)` from the browser;
  cljs iterates records itself.

Because shadow-cljs mangles property access on JS objects in advanced compilation, use
string property access (`(.-relativePathComponents record)` etc. are fine — CLJS leaves
these alone) and make sure `externs.js` covers any new `FileSystemObserver`/record
properties we read:
- `FileSystemObserver` (constructor + `observe`, `unobserve`, `disconnect`)
- `FileSystemChangeRecord` / record fields: `type`, `root`, `changedHandle`,
  `relativePathComponents`, `relativePathMovedFrom`
- We already use `FileSystemDirectoryHandle` methods (`getFileHandle`,
  `getDirectoryHandle`, `values`, `queryPermission`, `requestPermission`) elsewhere.

Check `externs.js` at the repo root when implementing; add whatever's missing. Otherwise
release builds will break silently.

### 2. `src/main/frontend/fs/nfs.cljs` (main change)

Existing file (366 lines at `4a0225b7e`). Implements `Nfs` record on the `Fs` protocol.
Key existing pieces to reuse (DO NOT re-implement these — they're already there):

- `nfs-file-handles-cache` atom (line ~25) — caches `handle/<path>` → FS handle.
- `add-nfs-file-handle!` / `remove-nfs-file-handle!`.
- `await-permission-granted` — promise that resolves when user has granted access.
- The filter predicate used in `readdir-and-reload-all-handles` and
  `get-files-and-reload-all-handles`:
  ```clojure
  (fn [file]
    (let [rpath (string/replace-first (.-webkitRelativePath file) (str root-dir "/") "")
          ext (util/get-file-ext rpath)]
      (or  (string/blank? rpath)
           (string/starts-with? rpath ".")
           (string/starts-with? rpath "logseq/bak")
           (string/starts-with? rpath "logseq/version-files")
           (not (contains? #{"md" "org" "excalidraw" "edn" "css"} ext)))))
  ```
  **Extract this to a private `ignored?` helper** that takes a relative path + ext and
  returns a boolean — then call it from both the existing readdir paths and the new
  observer callback. Do not duplicate.

- `watch-dir!` and `unwatch-dir!` at the bottom currently return `nil`:
  ```clojure
  (watch-dir! [_this _dir _options] nil)
  (unwatch-dir! [_this _dir] nil)
  ```
  Replace both.

#### New state

```clojure
(defonce *observers (atom {})) ;; dir -> FileSystemObserver
```

#### `watch-dir!` implementation

```clojure
(watch-dir! [_this dir _options]
  (p/let [_ (await-permission-granted (str "logseq_local_" dir))
          handle-path (str "handle/" dir)
          root-handle (or (get-nfs-file-handle handle-path)
                          (idb/get-item handle-path))]
    (cond
      (not root-handle)
      (log/warn ::watch-dir-no-handle {:dir dir})

      (not (utils/fileSystemObserverSupported))
      (do
        (notification/show!
          "File system watching is unavailable in this browser. External changes will not be picked up automatically."
          :warning false)
        (log/warn ::fs-observer-unsupported))

      (get @*observers dir)
      nil ;; already watching

      :else
      (p/let [observer (utils/createFsObserver
                         root-handle
                         (fn [records _observer]
                           (doseq [record (array-seq records)]
                             (handle-observer-record! dir record))))]
        (swap! *observers assoc dir observer)))))
```

#### `unwatch-dir!` implementation

```clojure
(unwatch-dir! [_this dir]
  (when-let [observer (get @*observers dir)]
    (try (.disconnect observer)
         (catch :default e
           (log/warn ::unwatch-dir-disconnect-error {:dir dir :error e})))
    (swap! *observers dissoc dir)))
```

#### Helper `handle-observer-record!`

```clojure
(defn- handle-observer-record!
  [dir record]
  (let [observer-type (.-type record)
        changed-handle (.-changedHandle record)
        kind (when changed-handle (.-kind changed-handle))
        components (js->clj (.-relativePathComponents record))
        rel-path (string/join "/" components)
        full-path (path/path-normalize (path/path-join dir rel-path))
        handle-path (str "handle/" full-path)]
    (cond
      (= observer-type "errored")
      (do
        (log/error ::fs-observer-errored {:dir dir :record record})
        (notification/show!
          "File system watcher error. External changes may stop updating until you reload the graph."
          :error false))

      (= observer-type "unknown")
      (log/warn ::fs-observer-unknown {:dir dir :path full-path})

      ;; apply nfs ignore filter (skip for disappearance of directories where we may not have ext)
      (and (not= kind "directory")
           (ignored? (path/relative-path dir full-path)))
      nil

      (= observer-type "disappeared")
      (do
        (remove-nfs-file-handle! handle-path)
        (watcher-handler/handle-changed!
          (if (= kind "directory") "unlinkDir" "unlink")
          {:dir dir :path full-path :content nil :stat nil}))

      (= observer-type "moved")
      (let [from-components (some-> (.-relativePathMovedFrom record) js->clj)
            from-rel (when from-components (string/join "/" from-components))
            from-path (when from-rel (path/path-normalize (path/path-join dir from-rel)))]
        ;; emit unlink for old path, then add for new path (recursing via a synthetic record is not needed;
        ;; just do the two dispatches inline so we don't re-enter this function)
        (when from-path
          (remove-nfs-file-handle! (str "handle/" from-path))
          (watcher-handler/handle-changed!
            (if (= kind "directory") "unlinkDir" "unlink")
            {:dir dir :path from-path :content nil :stat nil}))
        (if (= kind "directory")
          (do
            (add-nfs-file-handle! handle-path changed-handle)
            (watcher-handler/handle-changed!
              "addDir" {:dir dir :path full-path :content nil :stat nil}))
          (p/let [file (.getFile changed-handle)
                  content (.text file)]
            (add-nfs-file-handle! handle-path changed-handle)
            (watcher-handler/handle-changed!
              "add"
              {:dir dir :path full-path :content content
               :stat {:mtime (.-lastModified file)
                      :ctime (.-lastModified file)
                      :size (.-size file)}}))))

      (and (= observer-type "appeared") (= kind "directory"))
      (do
        (add-nfs-file-handle! handle-path changed-handle)
        (watcher-handler/handle-changed!
          "addDir" {:dir dir :path full-path :content nil :stat nil}))

      (or (= observer-type "appeared") (= observer-type "modified"))
      (p/let [file (.getFile changed-handle)
              content (.text file)]
        (add-nfs-file-handle! handle-path changed-handle)
        (watcher-handler/handle-changed!
          (if (= observer-type "appeared") "add" "change")
          {:dir dir :path full-path :content content
           :stat {:mtime (.-lastModified file)
                  :ctime (.-lastModified file)
                  :size (.-size file)}})))))
```

Notes:
- `js->clj` on the `relativePathComponents` array: verify it's actually a JS array in
  Chrome's implementation (it is per spec — `FrozenArray<USVString>`). `array-seq` on the
  records collection is safe.
- `FileSystemObserver` doesn't expose `ctime`; reuse `lastModified` for both, matching the
  compromise downstream code already tolerates for Electron `add` events.
- `ignored?` takes a path relative to `dir` and returns `true` for dotfiles, `logseq/bak`,
  `logseq/version-files`, or extensions outside `#{"md" "org" "excalidraw" "edn" "css"}`.
  For directories we skip the filter (the handler decides whether to forward `addDir`).
- Don't forget to add the require: `[frontend.fs.watcher-handler :as watcher-handler]`
  and `[frontend.handler.notification :as notification]` is already there. No cycle:
  `watcher-handler` depends on `frontend.fs` (not `frontend.fs.nfs`).

### 3. `externs.js`

Add extern entries for:
- `FileSystemObserver` (+ `observe`, `unobserve`, `disconnect`).
- `FileSystemChangeRecord` fields: `type`, `root`, `changedHandle`,
  `relativePathComponents`, `relativePathMovedFrom`.

Failure to do this will silently break the release build (`yarn release`/advanced
compilation). Dev builds may still work.

## Wire-up (no new call sites needed)

`fs/watch-dir!` is already called for file-based graphs from:
- `src/main/frontend/handler/events.cljs:78`  (`:graph/added` defmethod)
- `src/main/frontend/handler/events.cljs:109` (inside `graph-switch`)
- `src/main/frontend/handler/file_based/events.cljs:232`
- `src/main/frontend/handler/file_based/file.cljs:263`

Implementing the two protocol methods on `Nfs` is enough — every existing call site will
start working.

`unwatch-dir!` is already called from `src/main/frontend/handler/file_based/file.cljs:262`
before re-watching. Graph switch will rely on this to clean up.

## Relevant files to read while implementing

- `src/main/frontend/fs/nfs.cljs` (366 lines) — the whole file is relevant.
- `src/main/frontend/fs/watcher_handler.cljs` — especially `handle-changed!` and
  `handle-add-and-change!` to see exactly what payload keys are required/used.
- `src/main/frontend/fs/protocol.cljs` — the `Fs` protocol signatures.
- `src/main/frontend/fs.cljs` — outer dispatch (`get-fs`, `watch-dir!`, `unwatch-dir!`).
- `src/main/frontend/utils.js` — existing JS-interop file; place new exports alongside
  `openDirectory`, `verifyPermission`, `nfsSupported`.
- `src/electron/electron/fs_watcher.cljs` — reference for what events the downstream
  handler expects (payload shape, global-dir handling, `awaitWriteFinish` semantics).
- `externs.js` at repo root — add observer externs here.

## Verification

1. `cd /Users/scen/code/logseq && git checkout -b feat/fs-observer 4a0225b7e` (ask user
   before running — large checkout).
2. `yarn install` (this commit predates the pnpm migration).
3. `yarn watch` (see `package.json` scripts at that commit for the dev task name).
4. Load the dev URL in Chrome. Use "Add a graph" to pick a local folder; grant readwrite
   permission.
5. **External add:** create `pages/foo.md` in OS file manager → within ~1s, a `foo` page
   appears in Logseq without a manual refresh.
6. **External edit:** modify `pages/foo.md` in another editor → open page updates.
7. **External delete:** delete `pages/foo.md` → page removed from the graph.
8. **External rename:** `git mv pages/foo.md pages/bar.md` → `foo` gone, `bar` present.
9. **Self-write no-op:** type in Logseq, save, then confirm the observer-fired `modified`
   event is suppressed by `handle-changed!`'s `(= content db-content)` check — no double
   re-render, no console spam, no `logseq/bak/` pollution from own writes.
10. **Filtering:** add `logseq/bak/x.md` and `.hidden.md` → no event reaches
    `watcher-handler` (add a temporary `prn` in `handle-observer-record!` during bring-up
    if needed).
11. **Ignored extensions:** add `foo.txt`, `foo.pdf` → filtered out.
12. **Unsupported browser path:** in devtools run `window.FileSystemObserver = undefined`
    before switching/opening the graph → exactly one warning notification; no thrown
    errors; app continues in no-watch mode.
13. **`unwatch-dir!`:** switch graphs and confirm `@*observers` shrinks and old observers'
    `.disconnect()` was called (add a temporary `prn` during bring-up).
14. **Permission revoked mid-session:** in Chrome site settings, revoke file system
    access, then write to the folder → observer should fire `errored`; confirm error
    notification appears.

## Out of scope

- Polling fallback for non-Chromium browsers — user is Chrome-only; the feature-detect
  path keeps the old no-watch behavior.
- Porting to current master. Web NFS is fully removed there; re-introducing it is a
  separate, much larger effort.
- Electron / mobile — untouched.
- Conflict-merge UI for external changes — the user chose auto-accept with `logseq/bak/`
  backup, matching existing Electron behavior.

## Key decisions already made with the user

- **Base commit:** `4a0225b7e` (May 12, 2025).
- **File scope:** match the existing NFS filter
  (`md`/`org`/`excalidraw`/`edn`/`css`; skip dotfiles, `logseq/bak/`, `logseq/version-files/`).
- **Error handling:** log + user notification on `errored` records and on feature
  unavailability.
- **Change policy on external edits:** auto-accept with `logseq/bak/` backup. No prompt.

## Pitfalls worth re-reading before implementing

1. Don't check out the branch without user confirmation — the previous plan-execution
   attempt was interrupted exactly at the `git checkout -b feat/fs-observer 4a0225b7e`
   step. Ask first, run in `/Users/scen/code/logseq` not Dropbox.
2. Add externs, or release builds will mangle `FileSystemObserver` / record field access.
3. Extract and reuse the ignored-path predicate — don't copy-paste it from
   `readdir-and-reload-all-handles`.
4. Avoid recursive callbacks: for `moved`, emit two events inline rather than re-entering
   `handle-observer-record!` with a synthetic record.
5. Self-write loop is prevented by `handle-changed!`'s equality check, not by us — don't
   invent a separate "is this a write we just did?" flag.
6. At `4a0225b7e` the build is yarn, not pnpm (pnpm migration was `79c25837c` on master
   much later). Use yarn commands.
