(ns teigen.channels
  "提言を届ける合法な経路の登録簿。**法定の手続きと運用上の窓口を混ぜない** ——
  意見公募は行政手続法が定める手続きで、期間と案件が無ければ存在しない。
  ホットラインは内閣府の運用窓口で、随時開いているが法定の応答義務は無い。
  この違いが governor の『開いているか』判定になる。

  `shomen.vocabulary/advocacy-channels` と同じ 4 つ。")

(def registry
  {:public-comment
   {:label "意見公募手続（e-Gov パブリック・コメント）"
    :legal-basis "行政手続法 §39〜§45（命令等を定めようとするときの意見公募手続）"
    :law-id "405AC0000000088"
    :portal "https://public-comment.e-gov.go.jp/"
    :who #{:individual :organisation}
    :always-open? false
    :requires #{:case-id :comment-window}
    :targets #{:ministry}
    :response "提出意見を考慮した結果とその理由を公示（§43）—— 追跡対象"
    :note "案の公示期間（原則 30 日以上、§39 ③）だけ開く。自分からは開けない。対象は命令等（政令・省令・審査基準等）で、法律そのものは対象外"}

   :regulatory-reform-hotline
   {:label "規制改革・行政手続ホットライン（内閣府 規制改革推進室）"
    :legal-basis "法定の手続きではない。内閣府設置法 §4（規制改革推進会議の事務）に基づく運用上の受付窓口"
    :portal "https://www8.cao.go.jp/kisei-kaikaku/kisei/hotline/index.html"
    :who #{:individual :organisation}
    :always-open? true
    :requires #{}
    :targets #{:ministry :administration :diet}
    :response "所管府省の回答が公表される（運用）"
    :note "随時。法人可。法律・政令・省令・運用のどの階層の提案も受ける"}

   :direct-request
   {:label "所管機関への運用改善要望"
    :legal-basis "法定の手続きではない（行政手続法 §36の3 の『処分等の求め』は法令違反の是正に限られ、ここでは使わない）"
    :portal nil
    :who #{:individual :organisation}
    :always-open? true
    :requires #{:addressee}
    :targets #{:administration}
    :response "任意"
    :note "法令改正を要さない運用（デジタル手続法 §6 の指定・システム整備）を求めるとき"}

   :petition
   {:label "請願（国会・地方議会）"
    :legal-basis "請願法・国会法 §79（紹介議員）"
    :portal nil
    :who #{:individual :organisation}
    :always-open? true
    :requires #{:introducing-member}
    :targets #{:diet}
    :handled-by :moushibumi
    :note "紹介議員を要する。teigen はここから提出しない —— 市民本人の請願は cloud-itonami/moushibumi、事業者の請願は人間が紹介議員を得てから"}})

(defn channel [k] (get registry k))

(defn open?
  "その経路が**いま**開いているか。意見公募は `ctx` の `:comment-window`
  （`{:case-id .. :opens \"YYYY-MM-DD\" :closes \"YYYY-MM-DD\"}`）と `:today` で判定。
  ISO 日付の文字列比較で足りる（同じ形式なので辞書順 = 時間順）。"
  [k ctx]
  (let [ch (channel k)]
    (cond
      (nil? ch) false
      (:always-open? ch) true
      (= k :public-comment)
      (let [{:keys [case-id opens closes]} (:comment-window ctx)
            today (:today ctx)]
        (boolean (and case-id opens closes today
                      (<= (compare opens today) 0)
                      (<= (compare today closes) 0))))
      :else false)))

(defn for-target
  "宛先（`:ministry` / `:administration` / `:diet`）に届く経路のうち、teigen 自身が
  提出できるもの（`:handled-by` が無いもの）。"
  [target]
  (->> registry
       (filter (fn [[_ ch]] (and (contains? (:targets ch) target) (nil? (:handled-by ch)))))
       (map key)
       set))
