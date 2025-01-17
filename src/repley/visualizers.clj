(ns repley.visualizers
  "Default visualizer implementation."
  (:require [repley.ui.chart :as chart]
            [repley.ui.edn :as edn]
            [repley.ui.table :as table]
            [repley.protocols :as p]
            [ripley.live.source :as source]
            [ripley.html :as h]
            [ring.util.io :as ring-io]
            [clojure.java.io :as io]
            [repley.ui.icon :as icon]
            [repley.repl :as repl]))

(def sample-size
  "How many items to check when testing collection items."
  10)

(defn- map-columns-and-data [data]
  (let [id repl/*result-id*]
    (when (map? data)
      {:columns [{:label "Key" :accessor key} {:label "Value" :accessor val}]
       :data data
       :on-row-click #(repl/nav! id (key %))})))

(defn- seq-of-maps-columns-and-data [data]
  (let [id repl/*result-id*]
    (when (and (sequential? data)
               (every? map? (take 10 data)))
      {:columns (sort-by :label
                         (for [k (into #{}
                                       (mapcat keys)
                                       data)]
                           {:label (str k) :accessor #(get % k)}))
       :data (map-indexed (fn [i obj]
                            (with-meta obj {::index i})) data)
       :on-row-click #(repl/nav! id (::index (meta %)))})))

(defn- csv-columns-and-data [data]
  (when (and (seq? data)
             (every? vector? (take 10 data)))
    (let [columns (first data)
          data (rest data)]
      {:columns (map-indexed (fn [i label]
                               {:label label :accessor #(nth % i)}) columns)
       :data data})))

(def columns-and-data (some-fn map-columns-and-data
                               seq-of-maps-columns-and-data
                               csv-columns-and-data))

(def supported-data? (comp boolean columns-and-data))

(defn table-visualizer [_repley-opts {:keys [enabled?]}]
  (when enabled?
    (reify p/Visualizer
      (label [_] "Table")
      (supports? [_ data]
        (supported-data? data))
      (precedence [_] 0)
      (render [_ data]
        (let [{:keys [columns data on-row-click]} (columns-and-data data)
              [data-source _] (source/use-state data)]
          (table/table
           {:columns columns
            :on-row-click on-row-click}
           data-source)))
      (ring-handler [_] nil))))

(defn file-size [f]
  (str (.format (java.text.NumberFormat/getIntegerInstance) (.length f)) " bytes"))

(defn file-visualizer [{prefix :prefix :as _repley-opts} {:keys [enabled? allow-download?]}]
  (let [downloads (atom {})
        download! #(swap! downloads assoc % (str (java.util.UUID/randomUUID)))]
    (when enabled?
      (reify p/Visualizer
        (label [_] "File")
        (supports? [_ data]
          (instance? java.io.File data))
        (precedence [_] 100)
        (render [_ data]
          (let [id repl/*result-id*]
            (h/html
             [:div
              [:p "File info"]
              [:div [:b "Name: "] (h/dyn! (.getName data))]
              [::h/if (.isFile data)
               ;; Show file size for regular files
               [:div [:b "Size: "] (h/dyn! (file-size data))]

               ;; Show listing of files for directories
               (table/table
                {:key #(.getAbsolutePath %)
                 :columns [{:label "Name" :accessor #(.getName %)}
                           {:label "Size" :accessor #(if (.isDirectory %)
                                                       "[DIR]"
                                                       (file-size %))}]
                 :on-row-click #(repl/nav-by! id (fn [_]
                                                   {:label (.getName %)
                                                    :value %}))}
                (source/static (.listFiles data)))]

              [::h/when (and (.isFile data) (.canRead data) allow-download?)
               [:button.btn {:on-click #(download! data)} (icon/download) "Download"]]
              [::h/live (source/computed #(get % data) downloads)
               (fn [id]
                 (let [url (str prefix "/file-visualizer/download?id=" id)]
                   (h/html
                    [:div [:a {:target :_blank :href url} "Download here"]])))]])))
        (ring-handler [_]
          (fn [{uri :uri q :query-string}]
            (when (= uri (str prefix "/file-visualizer/download"))
              (let [id (subs q 3)
                    file (some (fn [[file id*]]
                                 (when (= id* id) file)) @downloads)]
                (swap! downloads dissoc file)
                (when file
                  {:status 200
                   :headers {"Content-Type" "application/octet-stream"
                             "Content-Disposition" (str "attachment; filename=" (.getName file))}
                   :body (ring-io/piped-input-stream
                          (fn [out]
                            (with-open [in (io/input-stream file)]
                              (io/copy in out))))})))))))))

(defn edn-visualizer [_ {enabled? :enabled?}]
  (when enabled?
    (reify p/Visualizer
      (label [_] "Result")
      (supports? [_ _] true)
      (precedence [_] 0)
      (render [_ data]
        (edn/edn data))
      (ring-handler [_] nil))))

(defn throwable-visualizer [_ {enabled? :enabled?}]
  (when enabled?
    (reify p/Visualizer
      (label [_] "Throwable")
      (supports? [_ ex] (instance? java.lang.Throwable ex))
      (precedence [_] 100)
      (render [_ ex]
        (let [id repl/*result-id*
              type-label (.getName (type ex))
              msg (ex-message ex)
              data (ex-data ex)
              trace (.getStackTrace ex)
              cause (.getCause ex)
              cause-label (when cause
                            (str (.getName (type cause)) ": "
                                 (.getMessage cause)))]
          (h/html
           [:div
            [:div [:b "Type: "] type-label]
            [:div [:b "Message:​ "] msg]
            [::h/when cause-label
             [:div [:b "Cause: "]
              [:a {:on-click #(repl/nav-by! id
                                            (constantly
                                             {:label (.getName (type cause))
                                              :value cause}))}
               cause-label]]]
            [::h/when (seq data)
             [:div [:b "Data​ "]
              (edn/edn data)]]
            [:div [:b "Stack trace​ "]
             [:details
              [:summary (h/out! (count trace) " stack trace lines")]
              [:ul
               [::h/for [st trace
                         :let [cls (.getClassName st)
                               method (.getMethodName st)
                               file (.getFileName st)
                               line (.getLineNumber st)]]
                [:li cls "." method " (" file ":" line ")"]]]]]])))
      (ring-handler [_] nil))))

(defn- chart-supports? [_opts x]
  (and (map? x)
       (every? number? (take sample-size (vals x)))))

(defn- chart-render [_opts data]
  (h/html
   [:div.chart
    (chart/bar-chart
     {:label-accessor (comp str key)
      :value-accessor val}
     (source/static data))]))

(defn chart-visualizer [_ {enabled? :enabled? :as opts}]
  (when enabled?
    (reify p/Visualizer
      (label [_] "Chart")
      (supports? [_ data] (chart-supports? opts data))
      (precedence [_] 0)
      (render [_ data] (chart-render opts data))
      (ring-handler [_] nil))))

(defn default-visualizers
  [opts]
  (let [{v :visualizers :as opts} opts]
    (remove nil?
            [(edn-visualizer opts (:edn-visualizer v))
             (table-visualizer opts (:table-visualizer v))
             (file-visualizer opts (:file-visualizer v))
             (throwable-visualizer opts (:throwable-visualizer v))
             (chart-visualizer opts (:chart-visualizer v))])))
