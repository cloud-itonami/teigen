(ns teigen.compose
  "提言文の起草。**決定論的**で、LLM を使わない。

  本文に書けるのは shomen の entry が持っている事実だけ —— 手続き名、残る analog
  制約とその根拠条文、電子の道の状態とその根拠、求める措置。書いた者の意見は
  『求める措置』の 1 段落に閉じ、根拠は全部 `:citations` に列挙する。governor は
  『本文中の根拠が entry の根拠と一致するか』を検査するので、ここで entry に無い
  条文を書くことはできない。

  LLM で文を整えたいときは、この出力を入力にして**本文を書き換えさせず**、
  言い回しだけを変える段を後ろに足す（そのときも citations は動かさない）。"
  (:require [kotoba.lang.text :as str]
            [shomen.classify :as c]))

(def ^:private ask-by-route
  {:ask-ministry "上記の命令等を改正し、当該手続きの書面・押印・収入印紙による納付・出頭の要件を削除するか、電子情報処理組織による申請および電子納付を明文で認めること。"
   :ask-administration "法令が既に許している電子的手段（情報通信技術を活用した行政の推進等に関する法律 §6 の指定、電子納付の受付）を、当該手続きについて実際に提供すること。"
   :ask-legislature "当該法律を改正し、書面・収入印紙の要件を削除するか、電子的手段による履行を明文で認めること。"})

(defn- analog-line [a]
  (str "- " (name (:a/kind a)) "（" (name (:a/source a)) "）: " (:a/legal-basis a)))

(defn draft
  "entry + principal → `{:title :addressee :body :citations :route :channels}`。
  route が `:ask-*` でなければ nil（求めるものが無い）、`:unverified` なら
  `{:refused …}`。"
  [entry principal]
  (let [adv (c/advocacy entry)]
    (cond
      (nil? adv) nil
      (:refused adv) adv
      :else
      (let [remaining (c/remaining-analog entry)
            e (:req/electronic entry)
            title (str (:req/name entry) "における書面・収入印紙等の要件の見直しについて")
            body (str/join
                  "\n"
                  (concat
                   [(str "件名: " title)
                    ""
                    "1. 対象となる手続き"
                    (str "   " (:req/name entry) "（所管: " (name (:req/authority entry)) "）")
                    ""
                    "2. 現在も残っている要件とその根拠"]
                   (map #(str "   " (analog-line %)) remaining)
                   [""
                    "3. 電子的手段の現状"
                    (str "   状態: " (name (:e/status e)))
                    (str "   根拠: " (or (:e/legal-basis e) "（記載なし）"))
                    ""
                    "4. 求める措置"
                    (str "   " (get ask-by-route (:route adv)))
                    ""
                    "5. 理由"
                    "   申請の経路が電子化されている、または法令上電子的手段が許されているにもかかわらず、上記の要件のために紙・収入印紙・出頭が残り、申請者と行政機関の双方に、印紙の購入・貼付・郵送・窓口対応の負担が生じている。要件の階層が法律でない場合、命令等の改正または運用で解消できる。"
                    ""
                    (str "提出者: " (:org principal))]))]
        {:title title
         :addressee (:target adv)
         :route (:route adv)
         :channels (:channels adv)
         :body body
         :citations (vec (concat (map :a/legal-basis remaining)
                                 (when (:e/legal-basis e) [(:e/legal-basis e)])))
         :entry-id (:req/id entry)
         :principal (:org principal)}))))
