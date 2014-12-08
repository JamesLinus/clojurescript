;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljs.source-map
  (:require [clojure.string :as string]
            [clojure.set :as set]
            [cljs.source-map.base64-vlq :as base64-vlq]))

;; =============================================================================
;; All source map code in the file assumes the following in memory
;; representation of source map data.
;;
;; { file-name[String]
;;   { line[Integer]
;;     { col[Integer]
;;       [{ :gline ..., :gcol ..., :name ...}] } }
;;
;; The outer level is a sorted map where the entries are file name and
;; sorted map of line information, the keys are strings. The line
;; information is represented as a sorted map of of column
;; information, the keys are integers. The column information is a
;; sorted map where the keys are integers and values are a vector of
;; maps - these maps have the keys :gline and :gcol for the generated
;; line and column.  A :name key may be present if available.
;;
;; This representation simplifies merging ClojureScript source map
;; information with source map information generated by Google Closure
;; Compiler optimization. We can now trivially create the merged map
;; by using :gline and :gcol in the ClojureScript source map data to
;; extract final :gline and :gcol from the Google Closure source map.

;; -----------------------------------------------------------------------------
;; Utilities

(defn indexed-sources
  "Take a seq of source file names and return a map from
   file number to integer index."
  [sources]
  (->> sources
    (map-indexed (fn [a b] [a b]))
    (reduce (fn [m [i v]] (assoc m v i)) {})))

(defn source-compare
  "Take a seq of source file names and return a comparator
   that can be used to construct a sorted map."
  [sources]
  (let [sources (indexed-sources sources)]
    (fn [a b] (compare (sources a) (sources b)))))

;; -----------------------------------------------------------------------------
;; Decoding

(defn seg->map
  "Take a source map segment represented as a vector
   and return a map."
  [seg source-map]
  (let [[gcol source line col name] seg]
   {:gcol   gcol
    :source (aget (.split (aget source-map "sources" source) "?") 0)
    :line   line
    :col    col
    :name   (when-let [name (-> seg meta :name)]
              (aget source-map "names" name))}))

(defn seg-combine
  "Combine a source map segment vector and a relative
   source map segment vector and combine them to get
   an absolute segment posititon information as a vector."
  [seg relseg]
  (let [[gcol source line col name] seg
        [rgcol rsource rline rcol rname] relseg
        nseg [(+ gcol rgcol)
              (+ (or source 0) rsource)
              (+ (or line 0) rline)
              (+ (or col 0) rcol)
              (+ (or name 0) rname)]]
    (if name
      (with-meta nseg {:name (+ name rname)})
      nseg)))

(defn update-result
  "Helper for decode. Take an internal source map representation
   organized as nested sorted maps mapping file, line, and column
   and update it based on a segment map and generated line number."
  [result segmap gline]
  (let [{:keys [gcol source line col name]} segmap
        d {:gline gline
           :gcol gcol}
        d (if name (assoc d :name name) d)]
    (update-in result [source]
      (fnil (fn [m]
              (update-in m [line]
                (fnil (fn [m]
                        (update-in m [col]
                          (fnil (fn [v] (conj v d))
                            [])))
                      (sorted-map))))
            (sorted-map)))))

(defn decode-reverse
  "Convert a v3 source map JSON object into a nested sorted map 
   organized as file, line, and column that maps ClojureScript
   source locations to the generated JavaScript."
  ([source-map]
     (decode (aget source-map "mappings") source-map))
  ([mappings source-map]
     (let [sources (aget source-map "sources")
           relseg-init [0 0 0 0 0]
           lines (seq (string/split mappings #";"))]
       (loop [gline 0
              lines lines
              relseg relseg-init
              result (sorted-map-by (source-compare sources))]
         (if lines
           (let [line (first lines)
                 [result relseg]
                 (if (string/blank? line)
                   [result relseg]
                   (let [segs (seq (string/split line #","))]
                     (loop [segs segs relseg relseg result result]
                       (if segs
                         (let [seg (first segs)
                               nrelseg (seg-combine (base64-vlq/decode seg) relseg)]
                           (recur (next segs) nrelseg
                             (update-result result (seg->map nrelseg source-map) gline)))
                         [result relseg]))))]
             (recur (inc gline) (next lines) (assoc relseg 0 0) result))
           result)))))