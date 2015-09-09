(ns alda.cli
  (:require [boot.cli        :refer (defclifn)]
            [taoensso.timbre :as    timbre]
            [boot.core       :refer (merge-env!)]
            [clojure.string  :as    str]
            [clojure.pprint  :refer (pprint)]
            [alda.parser     :refer (parse-input)]
            [alda.version    :refer (-version-)]
            [alda.sound]))

(defn fluid-r3! 
  "Fetches FluidR3 dependency and returns the input stream handle."
  []
  (eval
    '(do (merge-env!
           :dependencies '[[org.bitbucket.daveyarwood/fluid-r3 "0.1.1"]])
         (require '[midi.soundfont.fluid-r3 :as fluid-r3])
         fluid-r3/sf2)))

(defclifn ^:alda-task parse
  "Parse some Alda code and print the results to the console."
  [f file FILE str  "The path to a file containing Alda code to parse."
   c code CODE str  "The string of Alda code to parse."
   l lisp      bool "Parse into alda.lisp code."
   m map       bool "Evaluate the score and show the resulting instruments/events map."]
  (if-not (or file code)
    (parse "--help")
    (let [alda-lisp-code (parse-input (if code code (slurp file)))]
      (when lisp
        (pprint alda-lisp-code))
      (when map
        (require 'alda.lisp)
        (println)
        (pprint (eval alda-lisp-code))))))

(defclifn ^:alda-task play
  "Parse some Alda code and play the resulting score."
  [f file        FILE str "The path to a file containing Alda code to play."
   c code        CODE str "The string of Alda code to play."
   ; TODO: implement smart buffering and remove the buffer options
   p pre-buffer  MS  int  "The number of milliseconds of lead time for buffering. (default: 0)"
   P post-buffer MS  int  "The number of milliseconds to keep the synth open after the score ends. (default: 1000)"
   s stock           bool "Use the default MIDI soundfont of your JVM, instead of FluidR3."]
  (require '[alda.lisp] 
           '[instaparse.core])
  (binding [alda.sound.midi/*midi-soundfont* (when-not stock (fluid-r3!))
            alda.sound/*play-opts* {:pre-buffer  (or pre-buffer 0)
                                    :post-buffer (or post-buffer 1000)
                                    :one-off?    true}]
    (if-not (or file code)
      (play "--help")
      (let [parsed (parse-input (if code code (slurp file)))]
        (if (instaparse.core/failure? parsed)
          (prn parsed) 
          (alda.sound/play! (eval parsed)))
        identity))))

(defclifn ^:alda-task repl
  "Starts an Alda Read-Evaluate-Play-Loop."
  [p pre-buffer  MS int  "The number of milliseconds of lead time for buffering. (default: 0)"
   P post-buffer MS int  "The number of milliseconds to wait after the score ends. (default: 0)"
   s stock          bool "Use the default MIDI soundfont of your JVM, instead of FluidR3."]
  (binding [alda.sound.midi/*midi-soundfont* (when-not stock (fluid-r3!))
            alda.sound/*play-opts* {:pre-buffer  pre-buffer
                                    :post-buffer post-buffer
                                    :async?      true}]
    (eval
      '(do 
         (require '[alda.repl])
         (alda.repl/start-repl!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def alda-tasks
  (into {'help    "Display this help text."
         'version "Display Alda version number."} 
    (for [[sym var] (ns-publics *ns*) 
          :when (:alda-task (meta var))
          :let [doc (:doc (meta var))
                help-blurb (apply str (take-while (partial not= \newline) doc))]]
      [sym help-blurb])))

(def help-text
  (format (str "alda v%s\n\nUsage:\n\n    alda <task> <options>\n\n"
               "To see options for a task:\n\n    alda <task> --help\n\n"
               "Tasks:\n\n%s")  
          -version-
          (str/join \newline 
                    (for [[task blurb] alda-tasks]
                      (str "    " task \tab blurb)))))

(defn- delegate
  [cmd args]
  (if (empty? args)
    (cmd "")
    (apply cmd args)))

(defn set-timbre-level!
  []
  (timbre/set-level! (if-let [level (System/getenv "TIMBRE_LEVEL")] 
                       (keyword (str/replace level #":" ""))
                       :warn)))

(defn -main [& [cmd & args]]
  (set-timbre-level!) 
  (case cmd
    nil         (println help-text)
    "help"      (println help-text)
    "--help"    (println help-text)
    "-h"        (println help-text)
    "version"   (printf "alda v%s\n" -version-)
    "--version" (printf "alda v%s\n" -version-)
    "-v"        (printf "alda v%s\n" -version-)
    "parse"     (delegate parse args)
    "play"      (delegate play args)
    "repl"      (delegate repl args)
    (printf "[alda] Invalid command '%s'.\n\n%s\n" cmd)))
