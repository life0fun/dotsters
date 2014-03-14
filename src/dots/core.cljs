(ns dots.core
  (:require
    [cljs.core.async :as async
      :refer [<! >! chan close! sliding-buffer put! alts! timeout]]
    [jayq.core :refer [$ append ajax inner css $deferred when done 
                       resolve pipe on bind attr offset] :as jq]
    [jayq.util :refer [log]]
    [crate.core :as crate]
    [clojure.string :refer [join blank? replace-first]]
    [clojure.set :refer [union]]
    [dots.board :refer [create-board start-screen render-screen score-screen  render-score
                        render-view render-position-updates render-remove-dots
                        render-dot-chain-update erase-dot-chain transition-dot-chain-state
                        dot-colors dot-color dot-index add-missing-dots
                        flash-color-on flash-color-off
                        dot-positions-for-focused-color]])
  (:require-macros [cljs.core.async.macros :as m :refer [go go-loop alt!]]))


; ------------------- chan related ----------------------------
; multi-wait on a list of chans, when event match pred, ret event.
(defn select-chan [pred chans]
  (go-loop []
    (let [[value ch] (alts! chans)]
      (if (pred value) 
          value 
          (recur)))))


; dont do too much event handler, put event direct into out-chan.
(defn click-chan [selector msg-name]
  (let [out-chan (chan)
        handler (fn [e] (jq/prevent e) (put! out-chan [msg-name]))]
    (on ($ "body") :click selector {} handler)
    (on ($ "body") "touchend" selector {} handler)
    out-chan))

; put a tuple, [msg-name {:x :y}] into out-chan
(defn mouseevent-chan [out-chan selector event msg-name]
  (bind ($ selector) event
        #(do
           (put! out-chan [msg-name {:x (.-pageX %) :y (.-pageY %)}]))))

(defn touchevent-chan [out-chan selector event msg-name]
  (bind ($ selector) event
        #(let [touch (aget (.-touches (.-originalEvent %)) 0)]
           (put! out-chan [msg-name {:x (.-pageX touch) :y (.-pageY touch)}]))))

; either of mousedown or touchstart, emit :drawstart to chan
(defn drawstart-chan [ichan selector]
  (mouseevent-chan ichan selector "mousedown" :drawstart)
  (touchevent-chan ichan selector "touchstart" :drawstart))

(defn drawend-chan [ichan selector]
  (mouseevent-chan ichan selector "mouseup" :drawend)
  (mouseevent-chan ichan selector "touchend" :drawend))

; emit [:draw {:x 1 :y 2}] to chan conti upon mouse move
(defn drawer-chan [ichan selector]
  (mouseevent-chan ichan selector "mousemove" :draw)
  (touchevent-chan ichan selector "touchmove" :draw))

; go-loop consumes chan stream. recur only when msgs are :draw
(defn get-drawing [input-chan out-chan]
  (go-loop [msg (<! input-chan)]
    (put! out-chan msg)
    (log "get-drawing " msg)  ; [:draw {:x 288, :y 305}] 
    (if (= (first msg) :draw) ; recur only when :draw, ret otherwise.
      (recur (<! input-chan)))
  ))

; all types of draw evt handlers just put evt data into chan, 
; detect draw start and end, and filter out draw evt to down-stream.
(defn draw-chan [selector]
  (let [input-chan (chan) out-chan   (chan)]
    (drawstart-chan input-chan selector)
    (drawend-chan   input-chan selector)
    (drawer-chan    input-chan selector)
    ; we put event to input-chan in either drawstart, drawend, draw
    (go-loop [[msg-name _ :as msg] (<! input-chan)]
      (when (= msg-name :drawstart)  ; after draw start, conti consume draw.
        (put! out-chan msg)
        (<! (get-drawing input-chan out-chan)))
      (recur (<! input-chan)))
    out-chan))


(defn dot-chain-cycle? [dot-chain]
  (and (< 3 (count dot-chain))
       ((set (butlast dot-chain)) (last dot-chain))))


; multi-wait draw-ch to find out which dots to be removed in dot-chain
(defn get-dots-to-remove 
  [draw-ch start-state]
  (go-loop [last-state nil 
            state start-state]
    (render-dot-chain-update last-state state)
    (if (dot-chain-cycle? (state :dot-chain))
      (let [color (dot-color state (-> state :dot-chain first))]
        (log "get-dots-to-remove before flash-color-on ")
        (flash-color-on color)
        (<! (select-chan (fn [[msg _]] (= msg :drawend)) [draw-ch]))
        (flash-color-off color)
        (erase-dot-chain)
        (assoc state :dot-chain (dot-positions-for-focused-color state) :exclude-color color))

      ; blocking on draw-ch
      (let [[msg point] (<! draw-ch)]
        (if (= msg :drawend)
          (do (erase-dot-chain) state)
          (recur state
                 (if-let [dot-pos ((state :dot-index) point)]
                    (assoc state :dot-chain (transition-dot-chain-state state dot-pos))
                    state)))))))


(defn game-timer [seconds]
  (go (loop [timer (timeout 1000) time seconds]
        (inner ($ ".time-val") time)
        (<! timer)
        (if (zero? time)
          time
          (recur (timeout 1000) (dec time))))))

(defn setup-game-state []
  (let [init-state {:board (create-board)}]
    (render-view init-state)
    (let [board-offset ((juxt :left :top) (offset ($ ".dots-game .board")))]
      (assoc init-state 
             :dot-index (partial dot-index board-offset)
             :dot-chain [] 
             :score 0))))


(defn game-loop [init-state draw-ch]
  (let [game-over-ch (game-timer 60)]
    (go-loop [state init-state]
      (render-score state)
      (render-position-updates state)
      (let [state (add-missing-dots state)]
        (<! (timeout 300))
        (render-position-updates state)
        (log "game loop multi-wait on get-dots-to-remove")
        (let [[value ch] (alts! [(get-dots-to-remove draw-ch state) game-over-ch])]
          (if (= ch game-over-ch)
            state ;; leave game loop
            (recur
              (let [{:keys [dot-chain exclude-color]} value]
                (log "game loop recur " dot-chain)
                (if (< 1 (count dot-chain))
                  (-> state
                      (render-remove-dots dot-chain)
                      (assoc :score (+ (state :score) (count (set dot-chain)))
                             :exclude-color exclude-color))
                  state)
                ))))))))

(defn app-loop []
  (let [draw-ch (draw-chan "body")
        start-chan (click-chan ".dots-game .start-new-game" :start-new-game)]
    (go
      (render-screen (start-screen))
      (<! (select-chan #(= [:start-new-game] %) [start-chan draw-ch]))
      (loop []
        (let [{:keys [score]} (<! (game-loop (setup-game-state) draw-ch))]
          (render-screen (score-screen score)))
          ; multi-wait on start-new-game from either start-chan(click start event) and draw-ch(mousemove event)
          (<! (select-chan #(= [:start-new-game] %) [start-chan draw-ch]))       
          (recur)))))


(app-loop)