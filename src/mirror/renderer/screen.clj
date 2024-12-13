(ns mirror.renderer.screen
  "render the tablet on screen"
  (:require
    [clojure.spec.alpha :as spec]
    [duct.logger :refer [log]]
    [integrant.core :as ig]
    [manifold.stream :as stm]
    [quil.core :as q]
    [quil.helpers.drawing :refer [line-join-points]]
    [quil.middleware :as qm])
  (:import
    (processing.core
      PApplet)))

(def ig-key :mirror.renderer/screen)

(derive ig-key :mirror/renderer)

(def max-x 20967)
(def max-y 15725)
(def max-pres 4095)

(def scale-value 25.0)
(def background 255)
(def color 0)

(spec/def ::x (spec/and nat-int? #(<= % max-x)))
(spec/def ::y (spec/and nat-int? #(<= % max-y)))
(spec/def ::pres (spec/and nat-int? #(<= % max-pres)))
(spec/def ::point (spec/tuple ::x ::y ::pres))
(spec/def ::stroke (spec/coll-of ::point  :kind vector?))
(spec/def ::cur-stroke ::stroke)
(spec/def ::cur-erasure ::stroke)
(spec/def ::strokes (spec/coll-of ::stroke :kind vector?))
(spec/def ::erasures (spec/coll-of ::stroke :kind vector?))
(spec/def ::action #{:out :pen :pen-touch :rubber :rubber-touch})

(spec/def ::state
  (spec/keys :req-un [::x ::y ::pres
                      ::action
                      ::cur-stroke
                      ::strokes
                      ::cur-erasure
                      ::erasures]))

(spec/def ::pen #{0 1})
(spec/def ::rubber #{0 1})
(spec/def ::touch #{0 1})

(spec/def ::event
  (spec/keys :opt-un [::x ::y ::pres
                      ::pen ::rubber ::touch]))

(def init-state
  {:x 0 :y 0 :pres 0
   :action :out
   :cur-stroke []
   :strokes []
   :cur-erasure []
   :erasures []})

(defn new-state
  "Return the new state generated by the EVENT from the OLD-STATE"
  [old-state event]
  (let [{:keys [x y pres
                action
                cur-stroke strokes
                cur-erasure erasures]} old-state
        new-x (get event :x x)
        new-y (get event :y y)
        new-pres (get event :pres pres)
        new-pen (get event :pen)
        new-touch (get event :touch)
        new-rubber (get event :rubber)
        new-action (cond
                     new-pen (if (= new-pen 1) :pen :out)
                     new-rubber (if (= new-rubber 1) :rubber :out)
                     new-touch (cond
                                 (and (= new-touch 1) (= action :pen)) :pen-touch
                                 (and (= new-touch 1) (= action :rubber)) :rubber-touch
                                 (and (zero? new-touch) (= action :pen-touch)) :pen
                                 (and (zero? new-touch) (= action :rubber-touch)) :rubber
                                 :else action)
                     :else action)]
    {:x new-x :y new-y :pres new-pres

     :action new-action

     :strokes
     (if (and (= new-action :pen) (= action :pen-touch))
       (conj strokes cur-stroke)
       strokes)

     :cur-stroke
     (if (= new-action :pen-touch)
       (conj cur-stroke [new-x new-y new-pres])
       [])

     :erasures
     (if (and (= new-action :rubber) (= action :rubber-touch))
       (conj erasures cur-erasure)
       erasures)

     :cur-erasure
     (if (= new-action :rubber-touch)
       (conj cur-erasure [new-x new-y new-pres])
       [])}))

(spec/fdef new-state
  :args (spec/cat :old-state ::state
          :event ::event)
  :ret ::state)

;; UI

(defn setup
  []
  (q/background 255)
  (q/stroke-weight 1)
  (q/smooth))

(defn scale
  [x]
  (Math/round (float (/ x scale-value))))

(defn get-points
  [stroke]
  (->> stroke
    (map (fn [[x y _]]
           [(scale y) (scale (- max-x x))]))
    dedupe
    (into [])))

(defn draw-strokes
  [strokes color]
  (q/stroke color)
  (let [sks (mapv get-points strokes)]
    (doseq [s sks]
      (let [line-args (line-join-points s)]
        (run! #(apply q/line %) line-args)))))

(defn draw-state
  [state]
  (draw-strokes (:strokes @state) color)
  (draw-strokes (:erasures @state) background))

(defn toggle-pause
  [pause?]
  (if @pause?
    (q/no-loop)
    (q/start-loop))
  (swap! pause? not))

(defn clean
  [state]
  (reset! state {:strokes [] :erasures []})
  (setup))

(defn ui-key-press
  [state pause? logger]
  (let  [raw-key (q/raw-key)
         key-code (q/key-code)]
    (log logger :debug ::key-press {:raw-key raw-key
                                    :key-code key-code})
    (case raw-key
      \space (toggle-pause  pause?)
      \c (clean state))))

(defn create-sketch
  [state logger]
  (let [pause? (atom false)
        screen {:width (scale max-y)
                :height (scale max-x)}]
    (q/sketch
      :title "mirror"
      :size [(:width screen) (:height screen)]
      :setup #'setup
      :draw #(draw-state state)
      :key-pressed #(ui-key-press state pause? logger)
      :middleware [qm/pause-on-error])))

;; integrant methods


(defmethod ig/init-key ig-key [_ config]
  (let [{:keys [logger]} config
        stream (stm/stream)
        state (atom init-state)]
    (stm/consume #(swap! state new-state %) stream)
    (log logger :info ::init)
    {:stream stream
     :state state
     :logger logger
     :skatch (create-sketch state logger)}))

(defmethod ig/halt-key! ig-key [_ sys]
  (let [{:keys [stream logger skatch]} sys]
    (stm/close! stream)
    (.exit ^PApplet skatch)
    (log logger :info ::halt)))
