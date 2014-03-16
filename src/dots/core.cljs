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


; ----------------------------------------------------------------------------
; go-loop [state] block read chan, recur conj state data.
; go-loop [msg (<! chan)] (if (pred msg) (>! msg out-chan)) (recur (<! chan))
; read chan in body of go-loop, recur with updated conj new state 
; or put predicated msg into out chan and return out chan.
; break out when expected msg is recvd.
; ret output chan, or waited for msg value.
; 
; go-loop [ msg (<! chan)]
;   when (= :drawstart msg)
;     (>! out-chan msg)
;     go-loop [msg (<! chan)]
;       (>! out-chan msg)
;       if (= :draw msg)
;         (recur (<! chan))  
;   (recur (<! chan))

; game-loop alts! draw-chan and timeout-chan
;   go-loop [state init-state]
;     [value ch] (alts! [(get-dots-to-remove draw-chan state) timeout-chan])
;     (recur (-> state (render-remove-dots dot-chain) (assoc :score x)))
;
; ----------------------------------------------------------------------------

; ------------------- chan related ----------------------------
; multi-wait on a list of chans, unitl predicated event appear
(defn multi-wait-until [pred chans]
  (go-loop []
    (let [[value ch] (alts! chans)]
      (if (pred value) 
        value 
        (recur)))))


; ret a chan wraps click event handler of specified selector.
(defn click-chan [selector msg-name]
  (let [out-chan (chan)
        handler (fn [e] (jq/prevent e) (put! out-chan [msg-name]))]
    (on ($ "body") :click selector {} handler)
    (on ($ "body") "touchend" selector {} handler)
    out-chan))

; a chan wraps selector's mouse move hdl, msg [msg-name {:x :y}]
(defn mouseevent-chan [out-chan selector event msg-name]
  (bind ($ selector) event
        #(do
          (put! out-chan [msg-name {:x (.-pageX %) :y (.-pageY %)}]))))

; a chan wraps selector's touch event msg [msg-name {:x :y}]
(defn touchevent-chan [out-chan selector event msg-name]
  (bind ($ selector) event
        #(let [touch (aget (.-touches (.-originalEvent %)) 0)]
          (put! out-chan [msg-name {:x (.-pageX touch) :y (.-pageY touch)}]))))


; div's mousedown evt hdl emits :drawstart msg into passed-in chan
(defn drawstart-chan [ichan selector]
  (mouseevent-chan ichan selector "mousedown" :drawstart)
  (touchevent-chan ichan selector "touchstart" :drawstart))

; div's mouseup evt hdl emits :drawend msg into passed-in chan
(defn drawend-chan [ichan selector]
  (mouseevent-chan ichan selector "mouseup" :drawend)
  (mouseevent-chan ichan selector "touchend" :drawend))

; div's mousemove hdl emits [:draw {:x 1 :y 2}] into passed-in chan.
(defn drawer-chan [ichan selector]
  (mouseevent-chan ichan selector "mousemove" :draw)
  (touchevent-chan ichan selector "touchmove" :draw))

; go-loop read :draw msg and put to out-chan, recursively only when msg is :draw
(defn get-drawing [draw-ichan draw-ochan]
  (go-loop [msg (<! draw-ichan)]
    (put! draw-ochan msg)
    (if (= (first msg) :draw) ; recur only when :draw, ret otherwise.
      (recur (<! draw-ichan)))))

; this fn encap details of go-loop reading and filtering of :draw evts
; and ret draw-ochan that contains [ :draw-start :draw :draw ... :draw-end ]
; div's mouse click or move evts handler put msg into draw-ichan.
; go-loop read chan. when msg is :start, inner go-loop to relay :draw msg to draw-ochan. 
(defn draw-chan [selector]
  (let [draw-ichan (chan) 
        draw-ochan (chan)]
    (drawstart-chan draw-ichan selector)
    (drawend-chan   draw-ichan selector)
    (drawer-chan    draw-ichan selector)
    ; outer goloop read msg from chan, start inner go-loop when :draw-start
    (go-loop [[msg-name _ :as msg] (<! draw-ichan)]
      ; :drawstart, state changes to :draw msg with inner goloop. inner loop ret only when :drawend
      (when (= msg-name :drawstart)
        (put! draw-ochan msg)
        (<! (get-drawing draw-ichan draw-ochan)))
      (recur (<! draw-ichan)))
    draw-ochan))


(defn dot-chain-cycle? [dot-chain]
  (and (< 3 (count dot-chain))
       ((set (butlast dot-chain)) (last dot-chain))))

; goloop read draw-chan, which contains [:draw-start :draw :draw-end] one by one.
; map :draw x,y to dots with board dots index pos, and store dots to state map :dot-chain.
(defn get-dots-to-remove 
  [draw-chan start-state]
  (go-loop [last-state nil 
            state start-state]
    (render-dot-chain-update last-state state)
    (if (dot-chain-cycle? (state :dot-chain))
      (let [color (dot-color state (-> state :dot-chain first))] ; color of first dot in dot-chain
        (flash-color-on color) ; add flash class to .board-area
        ; blocking wait until draw end evt from draw-chan, then remove all dots in :dot-chain
        (<! (multi-wait-until (fn [[msg _]] (= msg :drawend)) [draw-chan]))
        (flash-color-off color)
        (erase-dot-chain)  ; just reset (inner ($ ".dots-game .dot-highlights") ""))
        (assoc state :dot-chain (dot-positions-for-focused-color state) :exclude-color color))

      ; blocking on draw-chan until draw-chan ret a chan contains [:draw-start :draw ... :drawend ]
      ; read chan could block, and each read rets one and only one event.
      (let [[msg point] (<! draw-chan)]
        ; (log "get-dots-to-remove after read draw-chan " msg point)
        (if (= msg :drawend)
          (do (erase-dot-chain) state)  ; reset .dot-highlights ""
          (recur state   ; last state is cur state
                 (if-let [dot-pos ((state :dot-index) point)]
                    (assoc state :dot-chain (transition-dot-chain-state state dot-pos))
                    state)))))))


(defn game-timer [seconds]
  (go-loop [timeout-chan (timeout 1000)  ; timer time out every second 
            count-down seconds]
    (inner ($ ".time-val") count-down) ; update UI
    (<! timeout-chan)  ; sequence programming, block 1 second
    (if (zero? count-down)
      count-down
      (recur (timeout 1000) (dec count-down)))))


; goloop recur process :draw msg from draw-chan, ret score when game over.
(defn game-loop [init-state draw-chan]
  (let [game-over-timeout (game-timer 60)]
    (go-loop [state init-state]
      (render-score state)
      (render-position-updates state)
      (let [state (add-missing-dots state)]
        (<! (timeout 300))
        (render-position-updates state)
        ; wait for the reading of draw-chan ret dots in state map :dot-chain
        (let [[state ch] (alts! [(get-dots-to-remove draw-chan state) game-over-timeout])]
          (if (= ch game-over-timeout)
            state ;; leave game loop
            (recur  ; dots in draw-chan get maps to vec pos index and store in :dot-chain in state map
              (let [{:keys [dot-chain exclude-color]} state]  
                (log "game loop recur " dot-chain)  ; dot-chain = [[0 4] [1 4]]
                (if (< 1 (count dot-chain))
                  (-> state
                      (render-remove-dots dot-chain)
                      (assoc :score (+ (state :score) (count (set dot-chain)))
                             :exclude-color exclude-color))
                  state)
                ))))))))

;
; {:board [[ ; a vec of vec, each dot pos is a tuple has :color and :elem of html
;       {:color :blue, :elem #<[object HTMLDivElement]>} 
;       {:color :blue, :elem #<[object HTMLDivElement]>} 
;       {:color :purple, :elem #<[object HTMLDivElement]>} 
;    ]], 
;   :dot-index #<function (a) { ... }>, 
;   :dot-chain [], 
;   :score 0, 
;   :exclude-color nil} 
(defn setup-game-state []
  (let [init-state {:board (create-board)}]
    (render-view init-state)
    (let [board-offset ((juxt :left :top) (offset ($ ".dots-game .board")))]
      (assoc init-state 
             :dot-index (partial dot-index board-offset)
             :dot-chain [] 
             :score 0))))

; collect draw msg from body into draw-chan, goloop remove all dots in draw-chan
(defn app-loop []
  (let [draw-chan (draw-chan "body")
        start-chan (click-chan ".dots-game .start-new-game" :start-new-game)]
    (go
      (render-screen (start-screen))
      (<! (multi-wait-until #(= [:start-new-game] %) [start-chan draw-chan]))
      (loop []
        (let [{:keys [score]} (<! (game-loop (setup-game-state) draw-chan))]
          (render-screen (score-screen score))
          (<! (multi-wait-until #(= [:start-new-game] %) [start-chan draw-chan]))       
          (recur))))))

(app-loop)