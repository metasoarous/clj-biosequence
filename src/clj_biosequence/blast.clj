(ns clj-biosequence.blast
  (:require [clojure.java.io :as io]
            [fs.core :as fs]
            [clj-commons-exec :as exec]
            [clojure.data.zip.xml :as zf]
            [clojure.zip :as zip]
            [clj-biosequence.core :as bios]
            [clojure.pprint :as pp]
            [clojure.string :as st]
            [clojure.data.xml :as xml])
  (:import [clj_biosequence.core fastaSequence]))

(import '(java.io BufferedReader StringReader))

(declare blastp-defaults run-blast get-sequence-from-blast-db blast-default-params merge-blasts store-blast-file with-iterations-in-search split-hsp-align)

;; macros

(defmacro with-iterations-in-search
  "Returns a handle to a lazy list of blastIteration objects 
   in a blastSearch object."
  [[handle blastsearch] & body]
  `(with-open [rdr# (io/reader (:src ~blastsearch))]
     (let [~handle (map #(->blastIteration (zip/node %))
                        (zf/xml-> (zip/xml-zip (xml/parse rdr#))
                                     :BlastOutput_iterations
                                     :Iteration))]
       ~@body)))

(defmacro with-blast-results
  "Takes a biosequence file, store or a collection of biosequences, runs BLAST 
   and returns a handle to the iterations in the resulting BLAST search. Basically
   a wrapper for `blast` and `with-iterations-in-search` to run BLAST without 
   worrying about output file creation and deletion."
  [[handle bioseqs params] & code]
  `(let [f# (fs/temp-file "blast")
         b# (blast ~bioseqs f# ~params)]
     (try
       (with-iterations-in-search [~handle b#]
         ~@code)
       (catch Exception e# (throw e#))
       (finally (fs/delete f#)))))

;; blast hsp

(defrecord blastHSP [src])

(defprotocol interfaceHSP
  (get-hsp-value [this key]
    "Takes a blastHSP object and returns the value corresponding to key.
     Keys are the keyword version of the XML nodes in the BLAST xml output. 
    All values are returned as strings. Typical BLAST HSP values are:
    :Hsp_bit-score
    :Hsp_score
    :Hsp_evalue
    :Hsp_query-from
    :Hsp_query-to
    :Hsp_hit-from
    :Hsp_hit-to
    :Hsp_positive
    :Hsp_identity
    :Hsp_gaps
    :Hsp_hitgaps
    :Hsp_querygaps
    :Hsp_qseq
    :Hsp_hseq
    :Hsp_midline
    :Hsp_align-len
    :Hsp_query-frame
    :Hsp_hit-frame
    :Hsp_num
    :Hsp_pattern-from
    :Hsp_pattern-to
    :Hsp_density")
  (alignment-string [this]
    "Takes a blastHSP object and returns a string of its alignment."))

(extend-protocol interfaceHSP

  blastHSP
  
  (get-hsp-value [this key]
    (if (= key :Hsp_midline)
      (first (:content
              (first
               (filter #(= (:tag %) :Hsp_midline)
                       (:content (:src this))))))
      (zf/xml1-> (zip/xml-zip (:src this))
                 key
                 zf/text)))

  (alignment-string [this]
    (pp/cl-format nil "~{~A~%~A~%~A~%~%~}"
                  (interleave
                   (split-hsp-align
                    (get-hsp-value this :Hsp_qseq)
                    (read-string (get-hsp-value this :Hsp_query-from)))
                   (map #(pp/cl-format nil "~7@< ~>~A~7< ~>"
                                       (apply str %))
                        (partition-all 58 (get-hsp-value this :Hsp_midline)))
                   (split-hsp-align
                    (get-hsp-value this :Hsp_hseq)
                    (read-string (get-hsp-value this :Hsp_hit-from)))))))

;; blast hit

(defrecord blastHit [src])

(defprotocol interfaceHit
  (get-hit-value [this key]
    "Takes a blastHit object and returns the value corresponding to key. 
     Keys are the keyword version of the XML nodes in the BLAST xml output. 
    All values are returned as strings. Typical BLAST Hit values are:
   :Hit_id
   :Hit_len
   :Hit_accession
   :Hit_def
   :Hit_num")
  (hsp-seq [this]
    "Takes a blastHit object and returns a lazy list of the blastHSP 
     objects contained in the hit.")
  (top-hsp [this]
    "Takes a blastHit object and returns the top scoring blastHSP 
     object in the hit. Returns an empty blastHSP if blastHit was empty.")
  (hit-bit-score [this]
    "Takes a blastHit object and returns the bit score of the top scoring HSP
     in the hit. Returns 0 if the blastHit was empty.")
  (hit-string [this]
    "A convenience function that takes a blastHit object and returns a formatted
     summary of the top scoring HSP in the hit. Includes accession, bit score
     (of the top scoring hit), definition of the hit and the alignment. Returns 
    'No hits in search' if empty blastHit. Example output:

   Accession: sp|Q8HY10|CLC4M_NOMCO
   Bit score: 50.0617822382917
   Def: C-type lectin domain family 4 member M OS=Nomascus concolor GN=CLEC
   Alignment:
   
   72     QAQQRDIEKEIESQKTSLTESWKKIIAEDIENRTNR-----SELKMEGQLSDLQEALT    124
          +++Q++I +E+   K ++ E  +K   ++I     R      EL  + +   + + LT       
   198    KSKQQEIYQELTRLKAAVGELPEKSKQQEIYQELTRLKAAVGELPDQSKQQQIYQELT    255"))

(extend-protocol interfaceHit

  blastHit

  (get-hit-value [this key]
    (zf/xml1-> (zip/xml-zip (:src this))
               key
               zf/text))

  (hsp-seq [this]
    (map #(->blastHSP (zip/node %))
         (zf/xml-> (zip/xml-zip (:src this))
                   :Hit_hsps
                   :Hsp)))

  (top-hsp [this]
    (or (first (hsp-seq this))
        (->blastHSP nil)))

  (hit-bit-score [this]
    (if (:src this)
      (read-string (get-hsp-value (top-hsp this) :Hsp_bit-score))
      0))

  (hit-string [this]
    (if (:src this)
      (let [hsp (top-hsp this)]
        (pp/cl-format nil "Accession: ~A~%Bit score: ~A~%Def: ~A~%Alignment:~%~%~A"
                      (get-hit-value this :Hit_id)
                      (get-hsp-value hsp :Hsp_bit-score)
                      (first (map #(apply str %)
                                  (partition-all 67 (get-hit-value this :Hit_def))))
                      (alignment-string hsp)))
      "No hits in search.\n")))

;; blast iteration

(defrecord blastIteration [src])

(defprotocol interfaceIteration
  (iteration-query-id [this]
    "Takes a blastIteration object and returns the query ID.")
  (hit-seq [this]
    "Returns a (lazy) list of blastHit objects from a blastIteration object.")
  (top-hit [this]
    "Returns the highest scoring blastHit object from a blastIteration object."))

(extend-protocol interfaceIteration

  blastIteration

  (iteration-query-id [this]
    (-> (zf/xml1-> (zip/xml-zip (:src this))
                   :Iteration_query-def
                   zf/text)
        (st/split #"\s")
        (first)))

  (hit-seq [this]
    (map #(->blastHit (zip/node %))
         (zf/xml-> (zip/xml-zip (:src this))
                   :Iteration_hits
                   :Hit)))

  (top-hit [this]
    (or (first (hit-seq this))
        (->blastHit nil))))

;; blastSearch

(defrecord blastSearch [src])

(defprotocol interfaceSearch
  (get-iteration-by-id [this accession]
    "Returns the blastIteration object for the specified biosequence from
     a blastSearch object.")
  (get-parameter-value [this key]
    "Returns the value of a blast parameter from a blastSearch object. Key 
     denotes parameter keys used in the blast xml. All values returned as
     strings. Typical keys include:
   :Parameters_matrix
   :Parameters_expect
   :Parameters_include
   :Parameters_sc-match
   :Parameters_sc-mismatch
   :Parameters_gap-open
   :Parameters_gap-extend
   :Parameters_filter")
  (database-searched [this]
    "Returns the path (as a string) of the database used in a blast search
     from a blastSearch object.")
  (program-used [this]
    "Returns the program used in a blast search from a blastSearch object."))

(extend-protocol interfaceSearch

  blastSearch

  (get-iteration-by-id [this accession]
    (with-iterations-in-search [l this]
      (some #(if (= accession (iteration-query-id %))
               %)
            l)))

  (get-parameter-value [this key]
    (with-open [rdr (io/reader (:src this))]
      (zf/xml1-> (zip/xml-zip (xml/parse rdr))
                 :BlastOutput_param
                 :Parameters
                 key
                 zf/text)))

  (database-searched [this]
    (with-open [rdr (io/reader (:src this))]
      (zf/xml1-> (zip/xml-zip (xml/parse rdr))
                 :BlastOutput_db
                 zf/text)))

  (program-used [this]
    (with-open [rdr (io/reader (:src this))]
      (zf/xml1-> (zip/xml-zip (xml/parse rdr))
                 :BlastOutput_program
                 zf/text))))

;; blast db

(defrecord blastDB [path type])

(defn get-sequence
  "Returns the specified sequence from a blastDB object as a fastaSequence object."
  [db id]
  (if id
    (first (bios/fasta-seq-string (get-sequence-from-blast-db db id)))))

(defn init-blast-db
  "initialises a blastDB object."
  [path type]
  (if-not (or (= :protein type) (= :nucleotide type))
    (throw (Throwable. "BLAST DB file type can be :protein or :nucleotide only."))
    (if (fs/exists? path)
      (->blastDB path type)
      (throw (Throwable. (str "File not found: " path))))))

;; blasting

(defn blast
  "Blasts all sequences in a biosequence file object and returns a blastSearch
   containing the location of the results. Takes a biosequence file, store or a 
   collection of biosequences, an file to which the blast results will be written
   and a params hash-map:
   :db   -   the BLAST database to be searched
   :program   -   the BLAST program to be used.
   :options   -  an optional hash-map of BLAST options. The keys corresponding to
                 the command line options of BLAST and the values the 
                 corresponding values.

   Returns a blastSearch object."
  [bioseqs out-file params]
  (with-open [wrt (io/writer out-file)]
    (let [r (bios/with-biosequences [l bioseqs]
              (doall (pmap #(let [i (fs/temp-file "seq-")
                                  o (fs/temp-file "blast-")]
                              (do
                                (doseq [s %]
                                  (spit i (bios/fasta-string s) :append true))
                                (run-blast (:program params) (:db params)
                                           (fs/absolute-path i)
                                           (fs/absolute-path o)
                                           (:options params))))
                           (partition-all 1000 l))))]
      (try (merge-blasts r wrt)
           (catch Exception e (throw e))
           (finally (doall (map #(fs/delete (:src %)) r))))
      (->blastSearch out-file))))

;; helpers

(defn- split-hsp-align
  "Helper function that takes a list of blast query or hit alignment strings
   and produces a list of strings formatted for the 'alignment-string' function.
   Basically it calculates the sequence numbers and sticks them on the start and end."
  [string begin]
  (loop [s (partition-all 58 string)
         b begin
         st []]
    (if (empty? s)
      st
      (let [l (count (remove #(= % \-) (first s)))]
        (recur (rest s)
               (+ b l)
               (conj st
                     (pp/cl-format nil "~7@<~A~>~A~7<~A~>" 
                                   b (apply str (first s)) (+ b (- l 1)))))))))

(defn- get-sequence-from-blast-db [db id]
  (let [s @(exec/sh (list "blastdbcmd" "-entry" id "-db" (:path db)))]
    (if (= 0 (:exit s))
      (:out s)
      (if (:err s)
        (throw (Throwable. (str "Blast error: " (:err s))))
        (throw (Throwable. (str "Exception: " (:exception s))))))))

(defn- blast-default-params
  [params in-file out-file db]
  (doall
   (remove #(nil? %)
           (flatten (seq (merge {"-evalue" "10"
                                 "-outfmt" "5"
                                 "-num_descriptions" "10"
                                 "-num_alignments" "10"
                                 "-max_target_seqs" "1"
                                 "-query"
                                 in-file
                                 "-out"
                                 out-file
                                 "-db" db}
                                params))))))

(defn- run-blast 
  [prog db in out params]
  "Need timeout"
  (try
    (let [defs (blast-default-params params
                                     in
                                     out
                                     (:path db))]
      (let [bl @(exec/sh (cons prog defs))]
        (if (= 0 (:exit bl))
          (->blastSearch out)
          (if (:err bl)
            (throw (Throwable. (str "Blast error: " (:err bl))))
            (throw (Throwable. (str "Exception: " (:exception bl))))))))
    (catch Exception e
      (do (fs/delete out)
          (throw e)))
    (finally (fs/delete in))))

(defn- merge-blasts
  [blasts strm]
  (xml/emit
   (xml/element
    :BlastOutput
    {}
    (with-open [rdr (io/reader (:src (first blasts)))]
      (doall (filter #((:tag %)
                       #{:BlastOutput_param :BlastOutput_db
                         :BlastOutput_program :BlastOutput_version})
                     (:content (xml/parse rdr)))))
    (xml/element
     :BlastOutput_iterations
     {}
     (doall (mapcat #(with-open [rdr (io/reader (:src %))]
                       (doall (for [x (xml-seq (xml/parse rdr))
                                    :when (= :BlastOutput_iterations
                                             (:tag x))]
                                (filter (fn [i]
                                          (= (:tag i) :Iteration))
                                        (:content x)))))
                    blasts))))
   strm))

(defn- store-blast-file
  [store]
  (let [bl-dir (str (fs/parent (:file store)) "/" "blast")]
    (if (fs/directory? bl-dir)
      (bios/time-stamped-file (str bl-dir "/" "blast"))
      (do (fs/mkdir bl-dir)
          (bios/time-stamped-file (str bl-dir "/" "blast"))))))