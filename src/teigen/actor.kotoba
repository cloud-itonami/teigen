(ns teigen.actor
  "actor 本体。`state + event -> next-state + effects`。1 run = 1 操作。

  永続化・送信・人間の署名 UI は host の仕事で、ここは **effect の記述**を返すだけ:

  | effect | 意味 |
  |---|---|
  | `[:ledger/append ev]` | 監査台帳に 1 行積む（append-only） |
  | `[:submit/dry-run …]` | 提出物を組み立てるが送らない（seed entry はここまで） |
  | `[:submit/live …]`    | 経路へ提出する。**governor が :allow を返したときだけ**現れる |
  | `[:human/sign-off …]` | 初回経路の live 提出。人が署名するまで止まる |
  | `[:refuse …]`         | 拒否。理由つき |

  ## 台帳

  `:ledger` は event の vector。`append` は seq を振り、前の行の seq を持つ
  （改竄検知は host の content-addressing に任せる。ここでは順序だけ）。

  ## event

  - `[:gap/observed {:entry .. :channel .. :mode .. :ctx ..}]` — shomen の entry が
    `:ask-*` に分類された、または意見公募が開いた
  - `[:human/signed {:submission-id ..}]` — hold されていた提出に人が署名した
  - `[:submission/acknowledged {:submission-id .. :receipt ..}]` — 受理番号・公示"
  (:require [teigen.compose :as compose]
            [teigen.governor :as gov]))

(defn init
  "`{:principal {:org :operator :consent {:signed-by :scope}} :ledger [] :held {}}`"
  [principal]
  {:principal principal :ledger [] :held {}})

(defn- append [ledger ev]
  (conj ledger (assoc ev :seq (count ledger) :prev (dec (count ledger)))))

(defn- submission-id [entry channel ctx]
  (str (name (:req/id entry)) "/" (name channel) "/" (or (:case-id ctx) "-")))

(defn step
  "純関数。`[next-state effects]` を返す。"
  [state [kind payload]]
  (case kind
    :gap/observed
    (let [{:keys [entry channel mode ctx]} payload
          principal (:principal state)
          draft (compose/draft entry principal)]
      (cond
        (nil? draft)
        (let [ev {:event :nothing-to-ask :entry-id (:req/id entry)}]
          [(update state :ledger append ev) [[:ledger/append ev]]])

        (:refused draft)
        (let [ev {:event :refused :entry-id (:req/id entry) :reason (:refused draft)}]
          [(update state :ledger append ev) [[:ledger/append ev] [:refuse ev]]])

        :else
        (let [verdict (gov/decide {:entry entry :draft draft :principal principal
                                   :channel channel :mode mode :ctx ctx :ledger (:ledger state)})
              sid (submission-id entry channel ctx)
              base {:entry-id (:req/id entry) :channel channel :mode mode
                    :case-id (:case-id ctx) :principal (:org principal)
                    :submission-id sid :decision (:decision verdict) :reasons (:reasons verdict)}]
          (case (:decision verdict)
            :deny
            (let [ev (assoc base :event :denied)]
              [(update state :ledger append ev) [[:ledger/append ev] [:refuse ev]]])

            :hold
            (let [ev (assoc base :event :held)]
              [(-> state (update :ledger append ev)
                   (assoc-in [:held sid] {:draft draft :channel channel :ctx ctx :entry entry}))
               [[:ledger/append ev] [:human/sign-off (assoc base :draft draft)]]])

            :allow
            (let [ev (assoc base :event (if (= mode :live) :submitted :dry-run))]
              [(update state :ledger append ev)
               [[:ledger/append ev]
                (if (= mode :live)
                  [:submit/live (assoc base :draft draft)]
                  [:submit/dry-run (assoc base :draft draft)])]])))))

    :human/signed
    (let [{:keys [submission-id signed-by]} payload
          held (get-in state [:held submission-id])]
      (if-not held
        (let [ev {:event :signature-without-hold :submission-id submission-id}]
          [(update state :ledger append ev) [[:ledger/append ev] [:refuse ev]]])
        (let [{:keys [draft channel ctx entry]} held
              ev {:event :submitted :entry-id (:req/id entry) :channel channel :mode :live
                  :case-id (:case-id ctx) :principal (:org (:principal state))
                  :submission-id submission-id :signed-by signed-by}]
          [(-> state (update :ledger append ev) (update :held dissoc submission-id))
           [[:ledger/append ev] [:submit/live (assoc ev :draft draft)]]])))

    :submission/acknowledged
    (let [ev (assoc payload :event :acknowledged)]
      [(update state :ledger append ev) [[:ledger/append ev]]])

    (let [ev {:event :unknown-event :kind kind}]
      [(update state :ledger append ev) [[:ledger/append ev] [:refuse ev]]])))
