(ns frontend.fs.nfs
  "Browser File System API based fs implementation.

   Rationale:
   - nfs-file-handles-cache stores all file & directory handle
   - idb stores top-level directory handle
   - readdir/get-files is called by re-index and initial watcher to init all handles"
  (:require [frontend.fs.protocol :as protocol]
            [frontend.util :as util]
            [clojure.set :as set]
            [clojure.string :as string]
            [frontend.idb :as idb]
            [promesa.core :as p]
            [lambdaisland.glogi :as log]
            [goog.object :as gobj]
            [frontend.db :as db]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.handler.notification :as notification]
            ["/frontend/utils" :as utils]
            [logseq.graph-parser.util :as gp-util]
            [logseq.common.path :as path]))

;; Cache the file handles in the memory so that
;; the browser will not keep asking permissions.
(defonce nfs-file-handles-cache (atom {}))

(defonce ^:private *observers (atom {}))

;; Polling fallback for Linux NFS: inotify (used by FileSystemObserver on Linux)
;; does not support NFS mounts. A periodic mtime scan works on any filesystem.
(defonce ^:private *poll-mtimes (atom {}))  ;; dir → {handle-path → mtime}
(defonce ^:private *poll-timers (atom {}))  ;; dir → js/setInterval id
(def ^:private poll-interval-ms 3000)

;; Echo suppression for FileSystemObserver: when we write a file ourselves,
;; remember its content hash so the observer event that bounces back can be
;; recognized and dropped. Without this, a fast typist can race the async
;; (.getFile) → (.text) snapshot read against the next DB update and trip
;; handle-changed!'s "external edit" branch (backup-and-overwrite).
(defonce ^:private *recent-self-writes (atom {})) ;; full-path → [{:hash :expires-at}, ...]
(def ^:private self-write-ttl-ms 10000)

(defn- now-ms [] (.now js/Date))

(defn- prune-self-writes [entries]
  (let [now (now-ms)]
    (vec (filter #(> (:expires-at %) now) entries))))

(defn- record-self-write!
  "Record that we are about to write `content` to `full-path`, so the resulting
  observer echo can be silenced. Returns a promise that resolves once the
  fingerprint is in the atom — await it before kicking off the actual write."
  [full-path content]
  (p/let [hash (utils/sha256Hex content)]
    (let [path-key (path/path-normalize full-path)
          entry {:hash hash :expires-at (+ (now-ms) self-write-ttl-ms)}]
      (swap! *recent-self-writes update path-key
             (fn [entries] (conj (prune-self-writes (or entries [])) entry)))
      nil)))

(defn- echo-of-self-write?
  "Promise<bool>. True iff `content` matches a non-expired self-write at
  `full-path`. The matched entry is consumed so a later genuine external write
  of the same bytes still goes through."
  [full-path content]
  (p/let [hash (utils/sha256Hex content)]
    (let [path-key (path/path-normalize full-path)
          entries (prune-self-writes (get @*recent-self-writes path-key []))
          match-idx (first (keep-indexed (fn [i e] (when (= (:hash e) hash) i)) entries))]
      (if match-idx
        (let [remaining (into (subvec entries 0 match-idx)
                              (subvec entries (inc match-idx)))]
          (swap! *recent-self-writes
                 (fn [m] (if (seq remaining)
                           (assoc m path-key remaining)
                           (dissoc m path-key))))
          true)
        false))))

(defn- ignored?
  "True if the relative path should be skipped by NFS (matches the filter used in
  readdir-and-reload-all-handles / get-files-and-reload-all-handles)."
  [rpath]
  (let [ext (util/get-file-ext rpath)]
    (or (string/blank? rpath)
        (string/starts-with? rpath ".")
        (string/starts-with? rpath "logseq/bak")
        (string/starts-with? rpath "logseq/version-files")
        (not (contains? #{"md" "org" "excalidraw" "edn" "css"} ext)))))

(defn- get-nfs-file-handle
  [handle-path]
  (get @nfs-file-handles-cache handle-path))

(defn add-nfs-file-handle!
  [handle-path handle]
  (prn ::DEBUG "add-nfs-file-handle!" handle-path)
  (swap! nfs-file-handles-cache assoc handle-path handle))

(defn remove-nfs-file-handle!
  [handle-path]
  (swap! nfs-file-handles-cache dissoc handle-path))

(defn- nfs-saved-handler
  [repo path file]
  (when-let [last-modified (gobj/get file "lastModified")]
    ;; TODO: extract
    (let [path (if (= \/ (first path))
                 (subs path 1)
                 path)]
      ;; Bad code
      (db/set-file-last-modified-at! repo path last-modified))))

(defn- verify-handle-permission
  [handle read-write?]
  (utils/verifyPermission handle read-write?))

(defn verify-permission
  [repo read-write?]
  (let [repo (or repo (state/get-current-repo))
        repo-dir (config/get-repo-dir repo)
        handle-path (str "handle/" repo-dir)
        handle (get-nfs-file-handle handle-path)]
    (p/then
     (utils/verifyPermission handle read-write?)
     (fn []
       (let [first-grant? (not (state/nfs-user-granted? repo))]
         (state/set-state! [:nfs/user-granted? repo] true)
         ;; Fire a one-shot event on the transition so consumers (e.g. the
         ;; auto-refresh-on-load hook) can run work that needs disk access.
         ;; Skipped on subsequent calls because verify-permission runs on
         ;; every read/write and we don't want to refresh repeatedly.
         (when first-grant?
           (state/pub-event! [:nfs/permission-granted repo])))
       true))))

(defn check-directory-permission!
  [repo]
  (when (config/local-db? repo)
    (p/let [repo-dir (config/get-repo-dir repo)
            handle-path (str "handle/" repo-dir)
            handle (idb/get-item handle-path)]
      (when handle
        (add-nfs-file-handle! handle-path handle)
        (verify-permission repo true)))))

(defn- contents-matched?
  [disk-content db-content]
  (when (and (string? disk-content) (string? db-content))
    (= (string/trim disk-content) (string/trim db-content))))

(defn- await-permission-granted
  "Guard against File System Access API permission, avoiding early access before granted"
  [repo]
  (if (state/nfs-user-granted? repo)
    (p/resolved true)
    (js/Promise. (fn [resolve reject]
                   (let [timer (atom nil)
                         timer' (js/setInterval (fn []
                                                  (when (state/nfs-user-granted? repo)
                                                    (js/clearInterval @timer)
                                                    (resolve true)))
                                                1000)
                         _ (reset! timer timer')]
                     (js/setTimeout (fn []
                                      (js/clearInterval timer)
                                      (reject false))
                                    100000))))))

(defn await-get-nfs-file-handle
  "for accessing File handle outside, ensuring user granted."
  [repo handle-path]
  (p/do!
   (await-permission-granted repo)
   (get-nfs-file-handle handle-path)))

(defn- readdir-and-reload-all-handles
  "Return list of filenames"
  [root-dir root-handle]
  (p/let [files (utils/getFiles root-handle
                                true
                                (fn [path entry]
                                  (let [handle-path (str "handle/" path)]
                                    ;; Same for all handles here, even for directories and ignored directories(for backing up)
                                    ;; FileSystemDirectoryHandle or FileSystemFileHandle
                                    (when-not (string/includes? path "/.")
                                      (add-nfs-file-handle! handle-path entry)))))]
    (->> files
         (remove (fn [file]
                   (let [rpath (string/replace-first (.-webkitRelativePath file) (str root-dir "/") "")]
                     (ignored? rpath))))
         (map (fn [file]
                (-> (.-webkitRelativePath file)
                    gp-util/path-normalize))))))


(defn- get-files-and-reload-all-handles
  "Return list of file objects"
  [root-dir root-handle]
  (p/let [files (utils/getFiles root-handle
                                true
                                (fn [path entry]
                                  (let [handle-path (str "handle/" path)]
                                    ;; Same for all handles here, even for directories and ignored directories(for backing up)
                                    ;; FileSystemDirectoryHandle or FileSystemFileHandle
                                    (when-not (string/includes? path "/.")
                                      (add-nfs-file-handle! handle-path entry)))))]
    (p/all (->> files
                (remove (fn [file]
                          (let [rpath (string/replace-first (.-webkitRelativePath file) (str root-dir "/") "")]
                            (ignored? rpath))))
                ;; Read out using .text, Promise<string>
                (map (fn [file]
                       (p/let [content (.text file)]
                         {:name        (.-name file)
                          :path        (-> (.-webkitRelativePath file)
                                           gp-util/path-normalize)
                          :mtime       (.-lastModified file)
                          :size        (.-size file)
                          :type        (.-kind (.-handle file))
                          :content     content
                          :file/file   file
                          :file/handle (.-handle file)})))))))

(defn- dispatch-watch-event!
  "Publish a file-watcher event. A handler registered in
  frontend.handler.events forwards it to
  frontend.fs.watcher-handler/handle-changed!. The indirection avoids a
  require cycle (nfs -> watcher-handler -> fs -> nfs)."
  [type payload]
  (state/pub-event! [:file-watcher/nfs-change type payload]))

(defn- handle-observer-record!
  [dir record]
  (let [observer-type (.-type record)
        changed-handle (.-changedHandle record)
        kind (when changed-handle (.-kind changed-handle))
        components (js->clj (.-relativePathComponents record))
        ;; rel-path is the key the watcher-handler pipeline expects; handle-path
        ;; keys into nfs-file-handles-cache and must include the dir prefix to
        ;; match the pattern used by readdir-and-reload-all-handles.
        rel-path (path/path-normalize (apply path/path-join components))
        full-path (path/path-normalize (apply path/path-join dir components))
        handle-path (str "handle/" full-path)]
    (cond
      (= observer-type "errored")
      (do
        (log/error ::fs-observer-errored {:dir dir})
        (notification/show!
         "File system watcher error. External changes may stop updating until you reload the graph."
         :error false))

      (= observer-type "unknown")
      (log/warn ::fs-observer-unknown {:dir dir :path rel-path})

      ;; Apply the NFS ignore filter for files; directories bypass it because
      ;; handle-changed! decides whether to forward addDir/unlinkDir.
      (and (not= kind "directory")
           (ignored? rel-path))
      nil

      (= observer-type "disappeared")
      (do
        (remove-nfs-file-handle! handle-path)
        (dispatch-watch-event!
         (if (= kind "directory") "unlinkDir" "unlink")
         {:dir dir :path rel-path :content nil :stat nil}))

      (= observer-type "moved")
      (let [from-components (some-> (.-relativePathMovedFrom record) js->clj)
            from-rel (when (seq from-components)
                       (path/path-normalize (apply path/path-join from-components)))
            from-full (when (seq from-components)
                        (path/path-normalize (apply path/path-join dir from-components)))]
        (when from-full
          (remove-nfs-file-handle! (str "handle/" from-full))
          (dispatch-watch-event!
           (if (= kind "directory") "unlinkDir" "unlink")
           {:dir dir :path from-rel :content nil :stat nil}))
        (if (= kind "directory")
          (do
            (add-nfs-file-handle! handle-path changed-handle)
            (dispatch-watch-event!
             "addDir" {:dir dir :path rel-path :content nil :stat nil}))
          (p/let [file (.getFile changed-handle)
                  content (.text file)]
            (add-nfs-file-handle! handle-path changed-handle)
            (dispatch-watch-event!
             "add"
             {:dir dir :path rel-path :content content
              :stat {:mtime (.-lastModified file)
                     :ctime (.-lastModified file)
                     :size (.-size file)}}))))

      (and (= observer-type "appeared") (= kind "directory"))
      (do
        (add-nfs-file-handle! handle-path changed-handle)
        (dispatch-watch-event!
         "addDir" {:dir dir :path rel-path :content nil :stat nil}))

      (or (= observer-type "appeared") (= observer-type "modified"))
      (p/let [file (.getFile changed-handle)
              content (.text file)
              echo? (echo-of-self-write? full-path content)]
        (add-nfs-file-handle! handle-path changed-handle)
        (when-not echo?
          (dispatch-watch-event!
           (if (= observer-type "appeared") "add" "change")
           {:dir dir :path rel-path :content content
            :stat {:mtime (.-lastModified file)
                   :ctime (.-lastModified file)
                   :size (.-size file)}}))))))

(defn- poll-dir-once!
  "Scan dir for mtime changes and dispatch watch events.
  Serves as the NFS fallback: inotify (backing FileSystemObserver on Linux)
  does not support NFS mounts, but mtime polling works on any filesystem."
  [dir root-handle]
  (-> (p/let [files        (utils/getFiles
                            root-handle true
                            (fn [fpath entry]
                              (when-not (string/includes? fpath "/.")
                                (add-nfs-file-handle! (str "handle/" fpath) entry))))
              prev-mtimes  (get @*poll-mtimes dir)  ;; nil on first run (init cycle)
              initializing? (nil? prev-mtimes)
              prev-mtimes  (or prev-mtimes {})
              seen-handles (atom #{})
              rel-files    (remove (fn [f]
                                     (ignored? (string/replace-first
                                                (.-webkitRelativePath f)
                                                (str dir "/") "")))
                                   files)]
        (when initializing?
          (log/info ::poll-init {:dir dir :file-count (count rel-files)}))
        (p/do!
         (p/all
          (map (fn [file]
                 (let [rpath     (string/replace-first
                                  (.-webkitRelativePath file) (str dir "/") "")
                       full-path (path/path-join dir rpath)
                       hp        (str "handle/" full-path)
                       mtime     (.-lastModified file)
                       prev      (get prev-mtimes hp)]
                   (swap! seen-handles conj hp)
                   (swap! *poll-mtimes assoc-in [dir hp] mtime)
                   (when (and (not initializing?) (not= mtime prev))
                     (p/let [content (.text file)
                             echo?   (echo-of-self-write? full-path content)]
                       (if echo?
                         (log/debug ::poll-echo-suppressed {:dir dir :path rpath})
                         (do
                           (log/info ::poll-dispatch
                                     {:dir dir :path rpath
                                      :event (if prev "change" "add")
                                      :mtime mtime})
                           (dispatch-watch-event!
                            (if prev "change" "add")
                            {:dir  dir  :path rpath  :content content
                             :stat {:mtime mtime :ctime mtime
                                    :size  (.-size file)}})))))))
               rel-files))
         (when-not initializing?
           (let [deleted (set/difference (set (keys prev-mtimes)) @seen-handles)]
             (when (seq deleted)
               (log/info ::poll-deleted {:dir dir :paths (mapv #(string/replace-first % (str "handle/" dir "/") "") deleted)}))
             (doseq [hp deleted]
               (let [rpath (string/replace-first hp (str "handle/" dir "/") "")]
                 (swap! *poll-mtimes update dir dissoc hp)
                 (remove-nfs-file-handle! hp)
                 (dispatch-watch-event!
                  "unlink"
                  {:dir dir :path rpath :content nil :stat nil})))))))
     (p/catch (fn [e]
                (log/warn ::poll-error {:dir dir :error (str e)})))))

(defn- start-poller! [dir root-handle]
  (when-not (get @*poll-timers dir)
    (log/info ::poll-start {:dir dir :interval-ms poll-interval-ms})
    (swap! *poll-timers assoc dir
           (js/setInterval #(poll-dir-once! dir root-handle) poll-interval-ms))
    ;; Run an immediate first cycle to initialise the mtime snapshot.
    (poll-dir-once! dir root-handle)))

(defn- stop-poller! [dir]
  (when-let [id (get @*poll-timers dir)]
    (log/info ::poll-stop {:dir dir})
    (js/clearInterval id)
    (swap! *poll-timers dissoc dir)
    (swap! *poll-mtimes dissoc dir)))

(defrecord ^:large-vars/cleanup-todo Nfs []
  protocol/Fs
  (mkdir! [_this dir]
    (let [dir (path/path-normalize dir)
          parent-dir (path/parent dir)

          parent-handle-path (str "handle/" parent-dir)]
      (-> (p/let [parent-handle (or (get-nfs-file-handle parent-handle-path)
                                    (idb/get-item parent-handle-path))
                  _ (when parent-handle (verify-handle-permission parent-handle true))]
            (when parent-handle
              (p/let [new-dir-name (path/filename dir)
                      new-handle (.getDirectoryHandle ^js parent-handle new-dir-name
                                                      #js {:create true})
                      handle-path (str "handle/" dir)
                      _ (idb/set-item! handle-path new-handle)]
                (add-nfs-file-handle! handle-path new-handle)
                (println "dir created: " dir))))
          (p/catch (fn [error]
                     (js/console.debug "mkdir error: " error ", dir: " dir)
                     (throw error))))))

  (mkdir-recur! [this dir]
    (protocol/mkdir! this dir))

  (readdir [_this dir]
    ;; This method is only used for repo-dir and version-files dir
    ;; There's no Logseq Sync support for nfs. So assume dir is always a repo dir.
    (p/let [repo-url (str "logseq_local_" dir)
            _ (await-permission-granted repo-url)
            handle-path (str "handle/" dir)
            handle (or (get-nfs-file-handle handle-path)
                       (idb/get-item handle-path))
            _ (when handle
                (verify-handle-permission handle true))
            fpaths (if (string/includes? dir "/")
                     (js/console.error "ERROR: unimpl")
                     (readdir-and-reload-all-handles dir handle))]
      fpaths))

  (unlink! [this repo fpath _opts]
    (let [repo-dir (config/get-repo-dir repo)
          filename (path/filename fpath)
          handle-path (str "handle/" fpath)
          recycle-dir (path/path-join repo-dir config/app-name config/recycle-dir)]
      (->
       (p/let [_ (protocol/mkdir! this recycle-dir)
               handle (get-nfs-file-handle handle-path)
               file (.getFile handle)
               content (.text file)

               bak-handle (get-nfs-file-handle (str "handle/" recycle-dir))
               bak-filename (-> (path/relative-path repo-dir fpath)
                                (string/replace "/" "_")
                                (string/replace "\\" "_"))
               file-handle (.getFileHandle ^js bak-handle bak-filename #js {:create true})
               _ (utils/writeFile file-handle content)

               parent-dir (path/parent fpath)
               parent-handle (get-nfs-file-handle (str "handle/" parent-dir))
               _ (when parent-handle
                   (.removeEntry ^js parent-handle filename))]
         (idb/remove-item! handle-path)
         (remove-nfs-file-handle! handle-path))
       (p/catch (fn [error]
                  (log/error :unlink/path {:path fpath
                                           :error error}))))))

  (rmdir! [_this _dir]
    nil)

  (read-file [_this dir path _options]
    (p/let [fpath (path/path-join dir path)
            handle-path (str "handle/" fpath)]
      (p/let [handle (or (get-nfs-file-handle handle-path)
                         (idb/get-item handle-path))
              local-file (and handle (.getFile handle))]
        (and local-file (.text local-file)))))

  (write-file! [_this repo dir path content opts]
    ;; TODO: file backup handling
    (let [fpath (path/path-join dir path)
          ext (util/get-file-ext path)
          file-handle-path (str "handle/" fpath)]
      (p/let [file-handle (get-nfs-file-handle file-handle-path)]
        (if file-handle
          ;; file exist
          (p/let [local-file (.getFile file-handle)
                  disk-content (.text local-file)
                  db-content (db/get-file repo path)
                  contents-matched? (contents-matched? disk-content db-content)]
            (if (and
                 (not (string/blank? db-content))
                 (not (:skip-compare? opts))
                 (not contents-matched?)
                 (not (contains? #{"excalidraw" "edn" "css"} ext))
                 (not (string/includes? path "/.recycle/")))
              (state/pub-event! [:file/not-matched-from-disk path disk-content content])
              (p/let [_ (verify-permission repo true)
                      _ (record-self-write! fpath content)
                      _ (utils/writeFile file-handle content)
                      file (.getFile file-handle)]
                (when file
                  (db/set-file-content! repo path content)
                  (nfs-saved-handler repo path file)))))
          ;; file no-exist, write via parent dir handle
          (p/let [basename (path/filename fpath)
                  parent-dir (path/parent fpath)
                  parent-dir-handle-path (str "handle/" parent-dir)
                  parent-dir-handle (get-nfs-file-handle parent-dir-handle-path)]

            (if parent-dir-handle
              ;; create from directory handle
              (p/let [file-handle (.getFileHandle ^js parent-dir-handle basename #js {:create true})
                      _  (add-nfs-file-handle! file-handle-path file-handle)
                      file (.getFile file-handle)
                      text (.text file)]
                (if (string/blank? text)
                  (p/let [;; _ (idb/set-item! file-handle-path file-handle)
                          _ (record-self-write! fpath content)
                          _ (utils/writeFile file-handle content)
                          file (.getFile file-handle)]
                    (when file
                      (nfs-saved-handler repo path file)))
                  (do
                    (notification/show! (str "The file " path " already exists, please append the content if you need it.\n Unsaved content: \n" content)
                                        :warning
                                        false)
                    (state/pub-event! [:file/alter repo path text]))))

              ;; TODO(andelf): Create parent directory and write
              ;; Normally directory are created layer by layer. So it's safe to leave this unimplemented.
              (js/console.error "TODO: can not create directory hierarchy")))))))

  (rename! [this repo old-path new-path]
    (p/let [repo-dir (config/get-repo-dir repo)
            old-rpath (path/relative-path repo-dir old-path)
            new-rpath (path/relative-path repo-dir new-path)
            old-content (protocol/read-file this repo-dir old-rpath nil)
            _ (protocol/write-file! this repo repo-dir new-rpath old-content nil)
            _ (protocol/unlink! this repo old-path nil)]))

  (stat [_this fpath]
    (if-let [handle (get-nfs-file-handle (str "handle/" fpath))]
      (p/let [_ (verify-handle-permission handle true)
              file (.getFile handle)]
        (let [get-attr #(gobj/get file %)]
          {:last-modified-at (get-attr "lastModified")
           :size (get-attr "size")
           :path fpath
           :type (get-attr "type")}))
      (p/rejected "File not exists")))

  (open-dir [_this _dir]
    (p/let [files (utils/openDirectory #js {:recursive true
                                            :mode "readwrite"}
                                       (fn [path entry]
                                         (let [handle-path (str "handle/" path)]
                                           ;; Same all handles here, even for directories and ignored directories(for backing up)
                                           ;; FileSystemDirectoryHandle or FileSystemFileHandle
                                           (when-not (string/includes? path "/.")
                                             (add-nfs-file-handle! handle-path entry)))))
            dir-handle (first files) ;; FileSystemDirectoryHandle
            dir-name (.-name dir-handle)
            files (->> (next files)
                       (remove  (fn [file]
                                  (let [rpath (.-webkitRelativePath file) ;
                                        ; (string/replace-first (.-webkitRelativePath file) (str dir-name "/") "")
                                        ext (util/get-file-ext rpath)]
                                    (or  (string/blank? rpath)
                                         (string/starts-with? rpath ".")
                                         (string/starts-with? rpath "logseq/bak")
                                         (string/starts-with? rpath "logseq/version-files")
                                         (not (contains? #{"md" "org" "excalidraw" "edn" "css"} ext))))))
                       ;; Read out using .text, Promise<string>
                       (map (fn [file]
                              (js/console.log "handle" file)
                              (p/let [content (.text file)]
                                ;; path content size mtime
                                {:name        (.-name file)
                                 :path        (-> (.-webkitRelativePath file)
                                                  gp-util/path-normalize)
                                 :mtime       (.-lastModified file)
                                 :size        (.-size file)
                                 :type        (.-kind (.-handle file))
                                 :content     content
                                 ;; expose the following, they are used by the file system
                                 :file/file   file
                                 :file/handle (.-handle file)}))))
            files (p/all files)]
      (add-nfs-file-handle! (str "handle/" dir-name) dir-handle)
      (idb/set-item! (str "handle/" dir-name) dir-handle)
      {:path dir-name
       :files files}))

  (get-files [_this dir]
    (when (string/includes? dir "/")
      (js/console.error "BUG: get-files(nfs) only accepts repo-dir"))
    (p/let [handle-path (str "handle/" dir)
            handle (get-nfs-file-handle handle-path)
            files (get-files-and-reload-all-handles dir handle)]
      files))

  (watch-dir! [_this dir _options]
    (p/let [_ (await-permission-granted (str "logseq_local_" dir))
            handle-path (str "handle/" dir)
            root-handle (or (get-nfs-file-handle handle-path)
                            (idb/get-item handle-path))]
      (cond
        (not root-handle)
        (log/warn ::watch-dir-no-handle {:dir dir})

        ;; Already watching via observer and/or poller.
        (or (get @*observers dir) (get @*poll-timers dir))
        nil

        (utils/fileSystemObserverSupported)
        ;; Start FSO for instant notifications on local filesystems, plus
        ;; the poller as a fallback for NFS mounts where inotify is silent.
        (p/do!
         (p/let [observer (utils/createFsObserver
                           root-handle
                           (fn [records _observer]
                             (doseq [record (array-seq records)]
                               (handle-observer-record! dir record))))]
           (swap! *observers assoc dir observer))
         (start-poller! dir root-handle))

        :else
        ;; No FileSystemObserver (old/unsupported browser): fall back to polling.
        (do
          (notification/show!
           "File system observer is unavailable in this browser; polling for changes every 3 s instead."
           :warning false)
          (log/warn ::fs-observer-unsupported {:dir dir})
          (start-poller! dir root-handle)))))

  (unwatch-dir! [_this dir]
    (when-let [observer (get @*observers dir)]
      (try (.disconnect observer)
           (catch :default e
             (log/warn ::unwatch-dir-disconnect-error {:dir dir :error e})))
      (swap! *observers dissoc dir))
    (stop-poller! dir)))
