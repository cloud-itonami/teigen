(ns teigen.governor
  "提出の可否を、起草とは別の系統で判定する。起草側が何を書いても、ここが拒否
  した提出は行われない（actor の単一不変条件）。

  判定は `:allow` / `:deny` / `:hold`（人間の署名待ち）の 3 値で、**理由を全部返す**。

  | gate | 何を守るか |
  |---|---|
  | G1 principal      | 提出者は actor を運用する事業者自身。第三者のための提出（lobbying-for-hire）をしない |
  | G2 consent        | 事業者の DID 署名付き同意（scope `:teigen/submit`）が無ければ提出しない |
  | G3 verified       | live 提出は `:maintainer-verified` 以上の entry からだけ。seed からは dry-run まで |
  | G4 route          | `:ask-*` だけ。`:electronic-now` は求めるものが無く、`:unverified` は調べていない |
  | G5 channel-open   | 意見公募は案件と期間が無ければ存在しない。請願は teigen から出さない |
  | G6 citations      | 本文の根拠が entry の根拠と一致し、entry に無い条文を引いていない |
  | G7 non-partisan   | 政党名・投票・候補者への言及を含む本文は出さない |
  | G8 no-pii         | 本文にメールアドレス・電話番号・個人番号を書かない（連絡先は sealed field） |
  | G9 dedupe         | 同じ entry × 経路 × 案件を二重に出さない |
  | G10 first-time    | ある事業者がある経路を**初めて**使う提出は人間の署名待ち（`:hold`） |"
  (:require [clojure.string :as str]
            [teigen.channels :as ch]
            [shomen.classify :as c]))

(def partisan-terms
  "非党派 gate のための deny list。完全ではない床。**党名を増やすときは理由を書く。**"
  ["自民党" "自由民主党" "立憲民主" "公明党" "日本維新" "共産党" "国民民主" "れいわ" "社民党" "参政党"
   "に投票" "候補者" "支持します" "支持する" "当選" "落選"])

(def ^:private pii-patterns
  [[:email #"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"]
   [:phone #"0\d{1,4}-\d{1,4}-\d{3,4}"]
   [:my-number #"(?<!\d)\d{4}[ -]?\d{4}[ -]?\d{4}(?!\d)"]])

(def live-confidences #{:maintainer-verified :counsel-verified})

(defn- rank-confidence [k] (get {:unverified-seed 0 :maintainer-verified 1 :counsel-verified 2} k -1))

(defn decide
  "`{:entry :draft :principal :channel :mode (:dry-run|:live) :ctx {:today :comment-window :case-id}
  :ledger [..events]}` → `{:decision :allow|:deny|:hold :reasons [..]}`."
  [{:keys [entry draft principal channel mode ctx ledger]}]
  (let [route (c/route entry)
        chan (ch/channel channel)
        body (or (:body draft) "")
        entry-bases (set (concat (map :a/legal-basis (c/remaining-analog entry))
                                 (some-> (get-in entry [:req/electronic :e/legal-basis]) vector)))
        prior (filter #(= :submitted (:event %)) ledger)
        dup? (some #(and (= (:entry-id %) (:req/id entry))
                         (= (:channel %) channel)
                         (= (:case-id %) (:case-id ctx)))
                   prior)
        first-time? (not (some #(and (= (:principal %) (:org principal))
                                     (= (:channel %) channel)
                                     (= :live (:mode %)))
                               prior))
        denies
        (cond-> []
          (not= (:org principal) (:operator principal))
          (conj {:gate :G1 :detail "提出者が actor の運用事業者と一致しない —— 第三者のための提出はしない"})

          (not= :teigen/submit (get-in principal [:consent :scope]))
          (conj {:gate :G2 :detail "事業者の同意（scope :teigen/submit）が無い"})

          (not (string? (get-in principal [:consent :signed-by])))
          (conj {:gate :G2 :detail "同意に署名者 DID が無い"})

          (and (= mode :live) (not (contains? live-confidences (:req/confidence entry))))
          (conj {:gate :G3 :detail (str "live 提出には :maintainer-verified 以上が要る。entry は " (:req/confidence entry))})

          (not (contains? #{:ask-ministry :ask-administration :ask-legislature} route))
          (conj {:gate :G4 :detail (str "route " route " は提出対象でない（:electronic-now は求めるものが無い、:unverified は調べていない）")})

          (nil? chan)
          (conj {:gate :G5 :detail (str "未知の経路 " channel)})

          (and chan (:handled-by chan))
          (conj {:gate :G5 :detail (str (name channel) " は teigen から出さない（" (name (:handled-by chan)) "）")})

          (and chan (nil? (:handled-by chan)) (not (ch/open? channel ctx)))
          (conj {:gate :G5 :detail (str (name channel) " はいま開いていない（意見公募は案件番号と期間が要る）")})

          (and chan draft (not (contains? (:targets chan) (:addressee draft))))
          (conj {:gate :G5 :detail (str (name channel) " は宛先 " (:addressee draft) " に届かない")})

          (empty? (:citations draft))
          (conj {:gate :G6 :detail "根拠の無い提言は出さない"})

          (not (every? #(str/includes? body %) (:citations draft)))
          (conj {:gate :G6 :detail "citations の条文が本文に無い"})

          (not (every? entry-bases (:citations draft)))
          (conj {:gate :G6 :detail "entry に無い条文を引いている —— 起草側が根拠を創作した"})

          (some #(str/includes? body %) partisan-terms)
          (conj {:gate :G7 :detail "政党・投票・候補者への言及を含む"})

          (some (fn [[k re]] (re-find re body)) pii-patterns)
          (conj {:gate :G8 :detail "本文に連絡先・個人番号らしき文字列がある。連絡先は sealed field に"})

          dup?
          (conj {:gate :G9 :detail "同じ entry × 経路 × 案件を既に提出している"}))]
    (cond
      (seq denies) {:decision :deny :reasons denies}
      (and (= mode :live) first-time?)
      {:decision :hold
       :reasons [{:gate :G10 :detail (str (:org principal) " が " (name channel) " を使う初回の live 提出。人間の署名待ち")}]}
      :else {:decision :allow :reasons []})))

(defn confidence-ok-for-live? [entry]
  (>= (rank-confidence (:req/confidence entry)) 1))
