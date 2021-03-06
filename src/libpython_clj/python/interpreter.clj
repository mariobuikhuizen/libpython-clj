(ns libpython-clj.python.interpreter
  (:require [libpython-clj.jna :as libpy ]
            [libpython-clj.jna.base :as libpy-base]
            [libpython-clj.python.gc :as pygc]
            [libpython-clj.python.logging
             :refer [log-error log-warn log-info]]
            [tech.jna :as jna]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json])
  (:import [libpython_clj.jna JVMBridge PyObject DirectMapped]
           [java.util.concurrent.atomic AtomicLong]
           [com.sun.jna Pointer]
           [com.sun.jna.ptr PointerByReference]
           [java.io StringWriter]
           [java.nio.file Paths Path]))


(set! *warn-on-reflection* true)


(defn get-object-handle
  [obj]
  (System/identityHashCode obj))


(defn python-system-data [executable]
  (let [{:keys [out err exit]}
        (sh executable "-c" "import sys, json;
print(json.dumps(
{\"platform\":          sys.platform,
  \"prefix\":           sys.prefix,
  \"base_prefix\":      sys.base_prefix,
  \"executable\":       sys.executable,
  \"base_exec_prefix\": sys.base_exec_prefix,
  \"exec_prefix\":      sys.exec_prefix,
  \"version\":          list(sys.version_info)[:3]}))")]
    (when (= 0 exit)
      (json/read-str out :key-fn keyword))))

(defn python-system-info
  "An information map about the Python system information provided
  by a Python executable (string).

  :platform (operating system information)
  :prefix
  A string giving the site-specific directory prefix where the platform independent Python files are installed; by default, this is the string '/usr/local'. This can be set at build time with the --prefix argument to the configure script. The main collection of Python library modules is installed in the directory prefix/lib/pythonX.Y while the platform independent header files (all except pyconfig.h) are stored in prefix/include/pythonX.Y, where X.Y is the version number of Python, for example 3.2.
  Note If a virtual environment is in effect, this value will be changed in site.py to point to the virtual environment. The value for the Python installation will still be available, via base_prefix.

  :base-prefix
  Set during Python startup, before site.py is run, to the same value as prefix. If not running in a virtual environment, the values will stay the same; if site.py finds that a virtual environment is in use, the values of prefix and exec_prefix will be changed to point to the virtual environment, whereas base_prefix and base_exec_prefix will remain pointing to the base Python installation (the one which the virtual environment was created from).

  :executable
  A string giving the absolute path of the executable binary for the Python interpreter, on systems where this makes sense. If Python is unable to retrieve the real path to its executable, sys.executable will be an empty string or None.

  :exec-prefix
  A string giving the site-specific directory prefix where the platform-dependent Python files are installed; by default, this is also '/usr/local'. This can be set at build time with the --exec-prefix argument to the configure script. Specifically, all configuration files (e.g. the pyconfig.h header file) are installed in the directory exec_prefix/lib/pythonX.Y/config, and shared library modules are installed in exec_prefix/lib/pythonX.Y/lib-dynload, where X.Y is the version number of Python, for example 3.2.
  Note If a virtual environment is in effect, this value will be changed in site.py to point to the virtual environment. The value for the Python installation will still be available, via base_exec_prefix.

  :base-exec-prefix
  Set during Python startup, before site.py is run, to the same value as exec_prefix. If not running in a virtual environment, the values will stay the same; if site.py finds that a virtual environment is in use, the values of prefix and exec_prefix will be changed to point to the virtual environment, whereas base_prefix and base_exec_prefix will remain pointing to the base Python installation (the one which the virtual environment was created from).

  :version
  (list python-major python-minor python-micro)"
  [executable]
  (let [{platform         :platform
         prefix           :prefix
         base-prefix      :base_prefix
         executable       :executable
         exec-prefix      :exec_prefix
         base-exec-prefix :base_exec_prefix
         version          :version}
        (python-system-data executable)]
    {:platform         platform
     :prefix           prefix
     :base-prefix      base-prefix
     :executable       executable
     :exec-prefix      exec-prefix
     :base-exec-prefix base-exec-prefix
     :version          version}))


(defn python-library-regex [system-info]
  (let [{version  :version
         platform :platform} system-info
        [major minor micro]  (vec version)]
    (re-pattern
     (format
      (condp (partial =) (keyword platform)
        ;; TODO: not sure what the strings returned by
        ;;   ..: mac and windows are for sys.platform
        :linux   "libpython%s.%sm.so$"
        :mac     "libpython%s.%sm.dylib$"
        :windows "python%s.%sm.dll$")
      major minor))))

(defn python-library-paths
  "Returns vector of matching python libraries in order of:
  - virtual-env (library)
  - installation prefix (library)
  - default installation location
  - virtual-env (executable)
  - installation prefix (executable)
  - default executable location"
  [system-info python-regex]
  (transduce
   (comp
    (map io/file)
    (map file-seq)
    cat
    (map str)
    (filter #(re-find python-regex %)))
   (fn
     ([[seen results]] results)
     ([[seen? results] input]
      (if (not (seen? input))
        [(conj seen? input) (conj results input)]
        [seen? results])))
   ;; [seen? results]
   [#{} []]
   ((comp
     vec
     (juxt :base-prefix :prefix :base-exec-prefix :exec-prefix))
    system-info)))

(comment
  ;; library paths workflow

  (let [executable "python3.7"
        system-info (python-system-info executable)
        pyregex (python-library-regex system-info)]
    (python-library-paths system-info pyregex))
  ;;=> ["/usr/lib/x86_64-linux-gnu/libpython3.7m.so" "/usr/lib/python3.7/config-3.7m-x86_64-linux-gnu/libpython3.7m.so"]
  )

(defn- ignore-shell-errors
  [& args]
  (try
    (apply sh args)
    (catch Throwable e nil)))


(defn detect-startup-info
  [{:keys [library-path python-home python-executable]}]
  (log-info
   (str "Detecting startup-info for Python executable: "
        python-executable))
  (let [executable                 (or python-executable "python3")
        system-info                (python-system-info executable)
        python-home                (cond
                                     python-home
                                     python-home
                                     (seq (System/getenv "PYTHONHOME"))
                                     (System/getenv "PYTHONHOME")
                                     :else
                                     (:prefix system-info))
        java-library-path-addendum (when python-home
                                     (-> (Paths/get python-home
                                                    (into-array String ["lib"]))
                                         (.toString)))
        [ver-maj ver-med _ver-min] (:version system-info)
        lib-version                (format "%s.%s" ver-maj ver-med)
        libname                    (or library-path
                                       (when (seq lib-version)
                                         (str "python" lib-version "m")))
        retval
        {:python-home                python-home
         :lib-version                lib-version
         :libname                    libname
         :java-library-path-addendum java-library-path-addendum}]
    (log/infof "Startup info detected: %s" retval)
    retval))


;;All interpreters share the same type symbol table as types are uniform
;;across initializations.  So given an unknown item, we can in constant time
;;get the type of that item if we have seen it before.
(defrecord Interpreter [
                        interpreter-state* ;;Thread state, per interpreter
                        shared-state* ;;state shared among all interpreters
                        ])


;; Main interpreter booted up during initialize!
;; * in the right to indicate atom
(def main-interpreter* (atom nil))
(defn main-interpreter
  ^Interpreter []
  @main-interpreter*)



(defn handle-or-interpreter->interpreter
  [hdl-or-interp]
  (if (number? hdl-or-interp)
    (throw (Exception. "Interpreters are no long handles"))
    hdl-or-interp))


;;Bridge objects are generically created objects that can bridge between
;;python and java.  At the very least, they implement JVMBridge
(defn find-jvm-bridge-entry
  ^JVMBridge [handle interpreter]
  (when-let [interpreter (handle-or-interpreter->interpreter interpreter)]
    (when-let [^JVMBridge bridge-object (get-in @(:interpreter-state* interpreter)
                                                [:bridge-objects handle])]
      bridge-object)))


(defn get-jvm-bridge
  ^JVMBridge [handle interpreter]
  (if-let [bridge-obj (find-jvm-bridge-entry handle (main-interpreter))]
    (:jvm-bridge bridge-obj)
    (throw (Exception.
            (format "Unable to find bridge for interpreter %s and handle %s"
                    interpreter handle)))))


(defn register-bridge!
  [^JVMBridge bridge ^Pointer bridge-pyobject]
  (let [interpreter (.interpreter bridge)
        bridge-handle (Pointer/nativeValue bridge-pyobject)]
    (when (contains? (get-in @(:interpreter-state* interpreter)
                           [:bridge-objects])
                     bridge-handle)
      (throw (Exception. (format "Bridge already exists!! - 0x%x" bridge-handle))))
    (swap! (:interpreter-state* interpreter) assoc-in
           [:bridge-objects bridge-handle]
           {:jvm-bridge bridge
            :pyobject bridge-handle})
    :ok))


(defn unregister-bridge!
  [^JVMBridge bridge ^Pointer bridge-pyobject]
  (let [interpreter (.interpreter bridge)
        bridge-handle (Pointer/nativeValue bridge-pyobject)]
    (swap! (:interpreter-state* interpreter)
           update :bridge-objects dissoc bridge-handle)
    :ok))


(defn- construct-main-interpreter!
  [thread-state type-symbol-table]
  (swap!
   main-interpreter*
   (fn [existing-interpreter]
     (when existing-interpreter
       (throw (Exception. "Main interpreter is already constructed")))

     (let [retval (->Interpreter
                                 (atom {:thread-state thread-state
                                        :bridge-objects {}
                                        :sub-interpreters []})
                                 ;;This that have to live as long as the main
                                 ;;interpreter does
                                 (atom {:type-symbol-table type-symbol-table
                                        :forever []}))]
       retval)))
  :ok)


(defn- python-thread-state
  [interpreter]
  (get @(:interpreter-state* interpreter) :thread-state))


(defn release-gil!
  "non-reentrant pathway to release the gil.  It must not be held by this thread."
  [interpreter]
  (let [thread-state (libpy/PyEval_SaveThread)]
    (libpy-base/set-gil-thread-id! (libpy-base/current-thread-id) Long/MAX_VALUE)
    (assoc @(:interpreter-state* interpreter) :thread-state thread-state)))


(defn acquire-gil!
  "Non-reentrant pathway to acquire gil.  It must not be held by this thread."
  [interpreter]
  (libpy/PyEval_RestoreThread (python-thread-state interpreter))
  (libpy-base/set-gil-thread-id! Long/MAX_VALUE (libpy-base/current-thread-id)))


(defn swap-interpreters!
  "The gil must be held by this thread.  This swaps out the current interpreter
  to make a new one current."
  [old-interp new-interp]
  (libpy/PyThreadState_Swap (python-thread-state old-interp)
                            (python-thread-state new-interp)))


(defn main-interpreter-thread-id
  ^long []
  (.get ^AtomicLong libpy-base/gil-thread-id))



(defn ensure-interpreter
  ^Interpreter []
  (let [retval (main-interpreter)]
    (when-not retval
      (throw (Exception. "No interpreters found, perhaps an initialize! call is missing?")))
    retval))


(defn ensure-bound-interpreter
  []
  (let [interp (main-interpreter)]
    (if (and interp
             (= (libpy-base/current-thread-id) (main-interpreter-thread-id)))
      interp
      (throw (Exception. "No interpreters found, perhaps an initialize! call is missing?")))))


(defn py-type-keyword
  "Get a keyword that corresponds to the current type.  Uses global type symbol
  table. Add the type to the symbol table if it does not exist already."
  [typeobj]
  (let [type-addr (Pointer/nativeValue (libpy/as-pyobj typeobj))
        interpreter (ensure-bound-interpreter)
        symbol-table (-> (swap! (:shared-state* interpreter)
                                (fn [{:keys [type-symbol-table] :as shared-state}]
                                  (if-let [found-item (get type-symbol-table
                                                           type-addr)]
                                    shared-state
                                    (assoc-in shared-state [:type-symbol-table
                                                            type-addr]
                                              {:typename (libpy/get-type-name
                                                          typeobj)}))))
                         :type-symbol-table)]
    (get-in symbol-table [type-addr :typename])))



(defmacro with-gil
  "Grab the gil and use the main interpreter.  Do not grab gil if already grabbed"
  [& body]
  `(do
     (let [interp# (ensure-interpreter)
           ^AtomicLong bound-thread# libpy-base/gil-thread-id
           thread-id# (libpy-base/current-thread-id)]
       (locking interp#
         (let [new-binding?# (if-not (= thread-id# (.get bound-thread#))
                               (do
                                 (acquire-gil! interp#)
                                 true)
                               false)]
           (try
             ~@body
             (finally
               (pygc/clear-reference-queue)
               (when new-binding?#
                 (release-gil! interp#)))))))))


(defonce ^:dynamic *program-name* "")



(defn- find-python-lib-version
  []
  (let [{:keys [out err exit]} (ignore-shell-errors "python3" "--version")]
    (when (= 0 exit)
      ;;Anaconda prints version info only to the error stream.
      (when-let [version-info (first (filter seq [out err]))]
        (log/infof "Python detected: %s" version-info)
        (let [version-data (re-find #"\d+\.\d+\.\d+" version-info)
              parts (->> (s/split version-data #"\.")
                         (take 2)
                         seq)]
          (s/join "." parts))))))


(defn append-java-library-path!
  [new-search-path]
  (let [existing-paths (-> (System/getProperty "java.library.path")
                           (s/split #":"))]
    (when-not (contains? (set existing-paths) new-search-path)
      (let [new-path-str (s/join ":" (concat [new-search-path]
                                             existing-paths))]
        (log/infof "Setting java library path: %s" new-path-str)
        (System/setProperty "java.library.path" new-path-str)))))


(defonce ^:private python-home-wide-ptr* (atom nil))
(defonce ^:private python-path-wide-ptr* (atom nil))


(defn- try-load-python-library!
  [libname python-home-wide-ptr python-path-wide-ptr]
  (try
    (jna/load-library libname)
    (alter-var-root #'libpy-base/*python-library* (constantly libname))
    (when python-home-wide-ptr
      (libpy/Py_SetPythonHome python-home-wide-ptr))
    (when python-path-wide-ptr
      (libpy/Py_SetProgramName python-path-wide-ptr))
    (libpy/Py_InitializeEx 0)
    libname
    (catch Exception e)))


(defn- setup-direct-mapping!
  []
  (let [library (jna/load-library libpy-base/*python-library*)]
    (com.sun.jna.Native/register DirectMapped library)))


(defn initialize!
  [& {:keys [program-name
             library-path
             python-executable]
      :as options}]
  (when-not (main-interpreter)
    (log-info (str "Executing python initialize with options:" options) )
    (let [{:keys [python-home libname java-library-path-addendum] :as startup-info}
          (detect-startup-info options)
          library-names (cond
                          library-path
                          [library-path]
                          libname
                          (concat
                           [libname]
                           (libpy-base/library-names))
                          :else
                          (libpy-base/library-names))]
      (reset! python-home-wide-ptr* nil)
      (reset! python-path-wide-ptr* nil)
      (when python-home
        (append-java-library-path! java-library-path-addendum)
        ;;This can never be released if load-library succeeeds
        (reset! python-home-wide-ptr* (jna/string->wide-ptr python-home))
        (reset! python-path-wide-ptr* (jna/string->wide-ptr
                                       (format "%s/bin/python3"
                                               python-home))))
      (loop [[library-name & library-names] library-names]
        (if (and library-name
                 (not (try-load-python-library! library-name
                                                @python-home-wide-ptr*
                                                @python-path-wide-ptr*)))
          (recur library-names)))
      (setup-direct-mapping!))
    ;;Set program name
    (when-let [program-name (or program-name *program-name* "")]
      (pygc/with-stack-context
       (libpy/PySys_SetArgv 0 (-> program-name
                                  (jna/string->wide-ptr)))))
    (let [type-symbols (libpy/lookup-type-symbols)
          context (do
                    (libpy-base/set-gil-thread-id! Long/MAX_VALUE (libpy-base/current-thread-id))
                    (let [retval (libpy/PyEval_SaveThread)]
                      (libpy-base/set-gil-thread-id! (libpy-base/current-thread-id) Long/MAX_VALUE)
                      retval))]
      (construct-main-interpreter! context type-symbols))))


(def ^:dynamic *python-error-handler* nil)


(defn check-error-str
  "Function assumes python stdout and stderr have been redirected"
  []
  (with-gil
    (when-not (= nil (libpy/PyErr_Occurred))
      (if-not *python-error-handler*
        (let [custom-writer (StringWriter.)]
          (with-bindings {#'*err* custom-writer}
            (libpy/PyErr_Print))
          (.toString custom-writer))
        (*python-error-handler*)))))


(defn check-error-throw
  []
  (when-let [error-str (check-error-str)]
    (throw (Exception. ^String error-str))))


(defn check-error-log
  []
  (when-let [error-str (check-error-str)]
    (log-error error-str)))


(defn finalize!
  []
  (when-not (== Long/MAX_VALUE (.get ^AtomicLong libpy-base/gil-thread-id))
    (throw (Exception. (format "A thread still owns the interpreter: "
                               (.get ^AtomicLong libpy-base/gil-thread-id)))))
  (let [interp (ensure-interpreter)]
    (locking interp
      (check-error-throw)
      (log-info "executing python finalize!")
      (acquire-gil! interp)
      (let [finalize-value (libpy/Py_FinalizeEx)]
        (when-not (= 0 finalize-value)
          (log-error (format "PyFinalize returned nonzero value: %s" finalize-value)))))))


(defn conj-forever!
  [items]
  (let [interpreter (ensure-bound-interpreter)]
    (swap! (:shared-state* interpreter) update :forever conj items)
    :ok))


;;Sub interpreter work goes here
